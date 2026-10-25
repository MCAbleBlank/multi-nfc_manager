package com.multinfc.manager.nfc

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.SystemClock
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Experimental Android 15 fallback: raw NCI `CORE_SET_CONFIG` / `CORE_GET_CONFIG`
 * through the hidden `NfcAdapter.sendVendorNciMessage` API.
 *
 * Why this exists: the OnePlus vendor `setHceTypeAConfig` path is disabled on
 * Chinese-region firmware (`doSetHceTypeAConfig` returns false when
 * `isOverSeaRegion()` is false), and the Android OEM extension only ships with
 * Android 16 QPR2+. Raw NCI is the remaining path and is what Emula uses for
 * OnePlus Android 15 devices.
 *
 * Safety contract:
 *  - Only `LA_*` listen configuration is changed; no routing, RF, or secure
 *    element commands are touched.
 *  - Every parameter is read with GET_CONFIG and persisted *before* the first
 *    SET_CONFIG. If a complete snapshot cannot be read, activation is aborted.
 *  - Polling is paused for the SET and restored in a `finally` block.
 *  - Any failure after a SET attempts to restore the snapshot; if restoration
 *    fails the pending snapshot stays on disk and the UI offers a retry.
 *
 * Requirements: `WRITE_SECURE_SETTINGS` (the service enforces
 * `NfcPermissions.enforceAdminPermissions`) and an Activity for
 * `setDiscoveryTechnology`. Call [activate] / [deactivate] off the main thread.
 */
object RawNciUidEmulation {

    private const val MESSAGE_TYPE_COMMAND = 1
    private const val CORE_GID = 0
    private const val SET_CONFIG_OID = 2
    private const val GET_CONFIG_OID = 3
    private const val TRANSPORT_OK = 0
    private const val RESPONSE_TIMEOUT_SECONDS = 4L
    private const val SETTLE_MILLIS = 500L
    private const val FOCUS_TIMEOUT_MILLIS = 2_000L

    private const val PREFS = "raw_nci_uid_emulation"
    private const val PREF_PENDING = "pending"
    private const val PREF_CONFIG = "config"
    private const val PREF_DEFAULTS = "defaults"
    private const val PREF_UID = "uid"
    private const val PREF_FINGERPRINT = "fingerprint"

    /** Parameters touched by the legacy listen profile. */
    private val SNAPSHOT_IDS = intArrayOf(0x85, 0x30, 0x31, 0x32, 0x33, 0x58)

    private const val ID_NFCC_CONFIG_CONTROL = 0x85
    private const val ID_BIT_FRAME_SDD = 0x30
    private const val ID_PLATFORM_CONFIG = 0x31
    private const val ID_SEL_INFO = 0x32
    private const val ID_NFCID1 = 0x33
    private const val ID_RATS_TB1 = 0x58

    private val LEGACY_PROFILE = Profile(
        bitFrameSdd = 0x06,
        platformConfig = 0x0C,
        selInfo = 0x20,
        rats = 0x40,
    )

    data class Probe(
        val available: Boolean,
        val reason: String? = null,
        val secureSettingsGranted: Boolean = false,
        val pendingRestore: Boolean = false,
    )

    private data class Pending(
        val config: Map<Int, ByteArray>?,
        val defaults: Boolean,
    )

    private data class Profile(
        val bitFrameSdd: Byte,
        val platformConfig: Byte,
        val selInfo: Byte,
        val rats: Byte,
    )

    private data class Api(
        val adapter: NfcAdapter,
        val callbackClass: Class<*>,
        val sendMethod: Method,
        val registerMethod: Method,
        val unregisterMethod: Method,
    )

    fun probe(context: Context): Probe {
        val granted = canWriteSecureSettings(context)
        val pending = hasPendingRestore(context)
        if (Build.VERSION.SDK_INT < 35) {
            return Probe(false, "raw NCI 通道需要 Android 15+", granted, pending)
        }
        if (!"oneplus".equals(Build.MANUFACTURER, ignoreCase = true)) {
            return Probe(false, "raw NCI 通道当前仅对 OnePlus Android 15 启用", granted, pending)
        }
        return try {
            resolveApi(context)
            Probe(true, secureSettingsGranted = granted, pendingRestore = pending)
        } catch (failure: Throwable) {
            Probe(false, describe(failure), granted, pending)
        }
    }

    fun activate(context: Context, uid: ByteArray, activity: Activity?): UidEmulation.Outcome {
        if (uid.size !in UidCodec.supportedByteLengths) {
            return UidEmulation.Outcome.Failure("UID 必须为 4、7 或 10 字节")
        }
        if (!canWriteSecureSettings(context)) {
            return UidEmulation.Outcome.Failure(permissionMessage(context))
        }
        val api = try {
            resolveApi(context)
        } catch (failure: Throwable) {
            return UidEmulation.Outcome.Failure(describe(failure))
        }
        if (!api.adapter.isEnabled) return UidEmulation.Outcome.NfcDisabled

        var session: Session? = null
        var restoreConfig: Map<Int, ByteArray>? = null
        var wroteConfig = false
        return try {
            session = Session(api)
            val pending = loadPending(context)
            restoreConfig = pending?.config
                ?: runCatching { readConfiguration(session, SNAPSHOT_IDS) }.getOrNull()
            if (pending == null && !persistPending(context, restoreConfig)) {
                error("无法保存原始 NFC 配置")
            }

            val values = legacyValues(uid)
            withPollingPaused(api.adapter, activity) {
                wroteConfig = true
                session.setConfig(values)
            }
            Thread.sleep(SETTLE_MILLIS)

            var actual = session.readUid()
            if (!actual.contentEquals(uid)) {
                session.setConfig(values)
                Thread.sleep(120)
                actual = session.readUid()
            }
            if (!actual.contentEquals(uid)) {
                error("控制器未保留 UID（读回 ${UidCodec.toHex(actual)}）")
            }

            markActive(context, uid)
            UidEmulation.Outcome.Success
        } catch (failure: Throwable) {
            if (wroteConfig && session != null) {
                val values = restoreConfig ?: defaultConfig()
                runCatching {
                    withPollingPaused(api.adapter, activity) {
                        session.setConfig(values)
                    }
                }.onFailure { rollback ->
                    // Keep the pending snapshot so the settings page can retry.
                    return UidEmulation.Outcome.Failure(
                        describe(failure) + "；回滚失败：" + describe(rollback)
                    )
                }
                clearSnapshot(context)
            } else if (session == null) {
                clearSnapshot(context)
            }
            UidEmulation.Outcome.Failure(describe(failure))
        } finally {
            session?.close()
        }
    }

    fun deactivate(context: Context, activity: Activity?): UidEmulation.Outcome {
        val pending = loadPending(context) ?: return UidEmulation.Outcome.Success
        if (!canWriteSecureSettings(context)) {
            return UidEmulation.Outcome.Failure(permissionMessage(context))
        }
        val api = try {
            resolveApi(context)
        } catch (failure: Throwable) {
            return UidEmulation.Outcome.Failure("存在待恢复配置：" + describe(failure))
        }
        var session: Session? = null
        return try {
            val values = pending.config ?: defaultConfig()
            session = Session(api)
            withPollingPaused(api.adapter, activity) {
                session.setConfig(values)
            }
            if (!clearSnapshot(context)) {
                UidEmulation.Outcome.Failure("配置已恢复，但待恢复标记未能清除")
            } else {
                UidEmulation.Outcome.Success
            }
        } catch (failure: Throwable) {
            UidEmulation.Outcome.Failure("恢复失败：" + describe(failure))
        } finally {
            session?.close()
        }
    }

    fun hasPendingRestore(context: Context): Boolean =
        preferences(context).getBoolean(PREF_PENDING, false)

    private fun resolveApi(context: Context): Api {
        val adapter = NfcAdapter.getDefaultAdapter(context)
            ?: throw IllegalStateException("设备没有可用的 NFC 适配器")
        val callbackClass = try {
            Class.forName("android.nfc.NfcAdapter\$NfcVendorNciCallback")
        } catch (failure: Throwable) {
            throw UnsupportedOperationException("系统未提供 NfcVendorNciCallback", failure)
        }
        val sendMethod = findMethod(
            adapter.javaClass,
            "sendVendorNciMessage",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            ByteArray::class.java,
        )
        val registerMethod = findMethod(
            adapter.javaClass,
            "registerNfcVendorNciCallback",
            Executor::class.java,
            callbackClass,
        )
        val unregisterMethod = findMethod(
            adapter.javaClass,
            "unregisterNfcVendorNciCallback",
            callbackClass,
        )
        return Api(adapter, callbackClass, sendMethod, registerMethod, unregisterMethod)
    }

    private class Session(private val api: Api) : AutoCloseable {
        @Volatile private var expectedGid = -1
        @Volatile private var expectedOid = -1
        @Volatile private var responseLatch: CountDownLatch? = null
        @Volatile private var responsePayload: ByteArray? = null

        private val callback: Any

        init {
            val sessionRef = arrayOfNulls<Session>(1)
            val handler = InvocationHandler { proxy, method, arguments ->
                if (method.declaringClass == Any::class.java) {
                    when (method.name) {
                        "toString" -> "RawNciCallback"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> arguments != null && arguments.size == 1 &&
                            proxy === arguments[0]
                        else -> null
                    }
                } else {
                    val session = sessionRef[0]
                    if (session != null && method.name == "onVendorNciResponse" &&
                        arguments != null && arguments.size == 3
                    ) {
                        val gid = arguments[0] as? Int
                        val oid = arguments[1] as? Int
                        val latch = session.responseLatch
                        if (latch != null && gid == session.expectedGid &&
                            oid == session.expectedOid
                        ) {
                            val payload = arguments[2] as? ByteArray
                            session.responsePayload = payload?.copyOf() ?: ByteArray(0)
                            latch.countDown()
                        }
                    }
                    null
                }
            }
            callback = Proxy.newProxyInstance(
                api.callbackClass.classLoader,
                arrayOf(api.callbackClass),
                handler,
            )
            sessionRef[0] = this
            invoke(api.adapter, api.registerMethod, Executor { it.run() }, callback)
        }

        fun setConfig(values: Map<Int, ByteArray>) {
            val ids = values.keys.toIntArray()
            val payload = buildSetPayload(values, ids)
            val response = send(SET_CONFIG_OID, payload)
            requireOk(response, "SET_CONFIG")
        }

        fun readUid(): ByteArray {
            val config = getConfig(intArrayOf(ID_NFCID1))
            return config[ID_NFCID1] ?: error("GET_CONFIG 未返回 LA_NFCID1")
        }

        fun getConfig(ids: IntArray): Map<Int, ByteArray> {
            val payload = ByteArray(ids.size + 1)
            payload[0] = ids.size.toByte()
            ids.forEachIndexed { index, id -> payload[index + 1] = id.toByte() }
            val response = send(GET_CONFIG_OID, payload)
            requireOk(response, "GET_CONFIG")
            return parseGetConfig(response)
        }

        private fun send(oid: Int, payload: ByteArray): ByteArray {
            expectedGid = CORE_GID
            expectedOid = oid
            responsePayload = null
            val latch = CountDownLatch(1)
            responseLatch = latch
            try {
                val result = invoke(
                    api.adapter,
                    api.sendMethod,
                    MESSAGE_TYPE_COMMAND,
                    CORE_GID,
                    oid,
                    payload.copyOf(),
                )
                val status = result as? Int
                    ?: error("sendVendorNciMessage 返回了非整型结果：$result")
                if (status != TRANSPORT_OK) {
                    error("NFC 框架拒绝命令（状态 $status）")
                }
                if (!latch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    error("等待控制器响应超时")
                }
                return responsePayload?.copyOf() ?: ByteArray(0)
            } finally {
                expectedGid = -1
                expectedOid = -1
                responseLatch = null
            }
        }

        override fun close() {
            runCatching { invoke(api.adapter, api.unregisterMethod, callback) }
        }
    }

    private fun readConfiguration(session: Session, ids: IntArray): Map<Int, ByteArray> {
        val values = session.getConfig(ids)
        val missing = ids.filterNot { values.containsKey(it) }
        if (missing.isNotEmpty()) {
            error(
                "无法完整读取原始 NFC 配置，缺少 " +
                    missing.joinToString { "0x%02X".format(it) }
            )
        }
        return values
    }

    private fun legacyValues(uid: ByteArray): Map<Int, ByteArray> = linkedMapOf(
        ID_NFCC_CONFIG_CONTROL to byteArrayOf(0),
        ID_BIT_FRAME_SDD to byteArrayOf(LEGACY_PROFILE.bitFrameSdd),
        ID_PLATFORM_CONFIG to byteArrayOf(LEGACY_PROFILE.platformConfig),
        ID_SEL_INFO to byteArrayOf(LEGACY_PROFILE.selInfo),
        ID_NFCID1 to uid.copyOf(),
        ID_RATS_TB1 to byteArrayOf(LEGACY_PROFILE.rats),
    )

    /** Controller listen defaults, used when a full snapshot is unavailable. */
    private fun defaultConfig(): Map<Int, ByteArray> = linkedMapOf(
        ID_NFCC_CONFIG_CONTROL to byteArrayOf(1),
        ID_NFCID1 to ByteArray(0),
        ID_RATS_TB1 to byteArrayOf(0),
        0x59 to ByteArray(0),
    )

    private fun buildSetPayload(values: Map<Int, ByteArray>, ids: IntArray): ByteArray {
        var length = 1
        for (id in ids) {
            val value = values[id] ?: error("缺少参数 0x%02X".format(id))
            require(value.size <= 0xFF)
            length += 2 + value.size
        }
        val payload = ByteArray(length)
        payload[0] = ids.size.toByte()
        var offset = 1
        for (id in ids) {
            val value = values[id] ?: error("缺少参数 0x%02X".format(id))
            payload[offset++] = id.toByte()
            payload[offset++] = value.size.toByte()
            value.copyInto(payload, offset)
            offset += value.size
        }
        return payload
    }

    private fun parseGetConfig(response: ByteArray): Map<Int, ByteArray> {
        if (response.size < 2) error("GET_CONFIG 响应被截断")
        val count = response[1].toInt() and 0xFF
        var offset = 2
        val values = LinkedHashMap<Int, ByteArray>()
        repeat(count) {
            if (offset + 2 > response.size) error("GET_CONFIG TLV 头被截断")
            val id = response[offset++].toInt() and 0xFF
            val size = response[offset++].toInt() and 0xFF
            if (offset + size > response.size) error("参数 0x%02X 被截断".format(id))
            values[id] = response.copyOfRange(offset, offset + size)
            offset += size
        }
        return values
    }

    private fun requireOk(response: ByteArray, operation: String) {
        if (response.isEmpty()) error("$operation 没有响应")
        val status = response[0].toInt() and 0xFF
        if (status != 0) {
            val rejected = parseRejectedIds(response)
            val suffix = if (rejected.isEmpty()) "" else
                "，拒绝参数 " + rejected.joinToString { "0x%02X".format(it) }
            error("$operation 被控制器拒绝（状态 0x%02X%s）".format(status, suffix))
        }
    }

    private fun parseRejectedIds(response: ByteArray): List<Int> {
        if (response.size < 2) return emptyList()
        val count = response[1].toInt() and 0xFF
        if (response.size < count + 2) return emptyList()
        return (0 until count).map { response[it + 2].toInt() and 0xFF }
    }

    private fun withPollingPaused(
        adapter: NfcAdapter,
        activity: Activity?,
        block: () -> Unit,
    ) {
        if (pausePolling(adapter, activity)) {
            try {
                block()
            } finally {
                resumePolling(adapter, activity)
            }
        } else {
            // Last resort: run without pausing. The verification below will
            // fail visibly and restore the snapshot if the controller loses it.
            block()
        }
    }

    private fun pausePolling(adapter: NfcAdapter, activity: Activity?): Boolean {
        if (activity != null) {
            return runCatching {
                awaitWindowFocus(activity)
                setDiscoveryTechnology(adapter, activity)
                Thread.sleep(SETTLE_MILLIS)
                true
            }.getOrFalse()
        }
        return runCatching {
            val pause = findMethod(
                adapter.javaClass,
                "pausePolling",
                Int::class.javaPrimitiveType!!,
            )
            val result = invoke(adapter, pause, 40_000)
            check(result is Int && (result == 1 || result == 2)) {
                "pausePolling 返回 $result"
            }
            true
        }.getOrFalse()
    }

    private fun resumePolling(adapter: NfcAdapter, activity: Activity?) {
        if (activity != null) {
            runCatching {
                resetDiscoveryTechnology(adapter, activity)
                Thread.sleep(SETTLE_MILLIS)
            }
            return
        }
        runCatching {
            val resume = findMethod(adapter.javaClass, "resumePolling")
            invoke(adapter, resume)
        }
    }

    private fun setDiscoveryTechnology(adapter: NfcAdapter, activity: Activity) {
        val method = findMethod(
            adapter.javaClass,
            "setDiscoveryTechnology",
            Activity::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
        invoke(
            adapter,
            method,
            activity,
            NfcAdapter.FLAG_READER_DISABLE,
            NfcAdapter.FLAG_LISTEN_DISABLE,
        )
    }

    private fun resetDiscoveryTechnology(adapter: NfcAdapter, activity: Activity) {
        val method = findMethod(
            adapter.javaClass,
            "resetDiscoveryTechnology",
            Activity::class.java,
        )
        invoke(adapter, method, activity)
    }

    private fun awaitWindowFocus(activity: Activity) {
        val deadline = SystemClock.elapsedRealtime() + FOCUS_TIMEOUT_MILLIS
        while (!activity.hasWindowFocus()) {
            if (activity.isFinishing || activity.isDestroyed ||
                SystemClock.elapsedRealtime() >= deadline
            ) {
                error("应用需要保持在前台才能暂停 NFC 轮询")
            }
            Thread.sleep(20)
        }
    }

    private fun persistPending(
        context: Context,
        config: Map<Int, ByteArray>?,
    ): Boolean = preferences(context).edit()
        .putBoolean(PREF_PENDING, true)
        .putString(PREF_CONFIG, config?.let(::encodeConfig).orEmpty())
        .putBoolean(PREF_DEFAULTS, config == null)
        .putString(PREF_FINGERPRINT, Build.FINGERPRINT)
        .remove(PREF_UID)
        .commit()

    private fun markActive(context: Context, uid: ByteArray): Boolean =
        preferences(context).edit()
            .putBoolean(PREF_PENDING, true)
            .putString(PREF_UID, UidCodec.toHex(uid))
            .commit()

    private fun loadPending(context: Context): Pending? {
        val prefs = preferences(context)
        if (!prefs.getBoolean(PREF_PENDING, false)) return null
        val encoded = prefs.getString(PREF_CONFIG, "").orEmpty()
        val config = encoded.takeIf { it.isNotBlank() }?.let {
            runCatching { decodeConfig(it) }.getOrNull()
        }
        return Pending(config, config == null)
    }

    private fun clearSnapshot(context: Context): Boolean =
        preferences(context).edit().clear().commit()

    private fun encodeConfig(values: Map<Int, ByteArray>): String =
        values.entries.joinToString(";") { (id, value) ->
            "%02X=%s".format(id, UidCodec.toHex(value))
        }

    private fun decodeConfig(encoded: String): Map<Int, ByteArray> {
        val values = LinkedHashMap<Int, ByteArray>()
        for (entry in encoded.split(";")) {
            val separator = entry.indexOf('=')
            require(separator > 0)
            val id = entry.substring(0, separator).toInt(16)
            val hex = entry.substring(separator + 1)
            require(hex.length % 2 == 0)
            val bytes = ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            values[id] = bytes
        }
        return values
    }

    private fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun permissionMessage(context: Context): String =
        "需要 WRITE_SECURE_SETTINGS：adb shell pm grant " + context.packageName +
            " android.permission.WRITE_SECURE_SETTINGS"

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun findMethod(owner: Class<*>, name: String, vararg types: Class<*>): Method =
        try {
            owner.getMethod(name, *types)
        } catch (failure: NoSuchMethodException) {
            owner.getDeclaredMethod(name, *types)
        }

    private fun invoke(receiver: Any, method: Method, vararg arguments: Any?): Any? {
        runCatching { method.isAccessible = true }
        return method.invoke(receiver, *arguments)
    }

    private fun describe(failure: Throwable): String {
        var root = failure
        while (root is InvocationTargetException && root.cause != null) {
            root = root.cause!!
        }
        val message = root.message
        return root.javaClass.simpleName + if (message.isNullOrBlank()) "" else "：$message"
    }

    private fun Result<Boolean>.getOrFalse(): Boolean = getOrDefault(false)
}
