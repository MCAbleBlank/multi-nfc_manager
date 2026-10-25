package com.multinfc.manager.nfc

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Experimental OnePlus vendor HCE path for Android 15 devices.
 *
 * The OEM extension used by [UidEmulation] only exists on Android 16 QPR2+.
 * OnePlus exposes `com.vendor.nfc.VendorNfcAdapter#setHceTypeAConfig` through
 * the `nfcvendorlib` shared library, plus four `Settings.Global` keys that
 * describe the "access card" HCE configuration. This controller mirrors the
 * snapshot/activate/restore sequence used by Emula (GPL-3.0, no code copied):
 * capture the original settings, configure the controller, neutralize the
 * vendor RF guard, and restore the exact original state on stop.
 *
 * Requirements:
 *  - OnePlus device with `nfcvendorlib` and `setHceTypeAConfig`
 *  - `WRITE_SECURE_SETTINGS`, granted once over ADB
 *
 * Only the UID is reproduced; MIFARE sectors, keys and authentication are
 * not involved. Unverified vendor firmware can reject the call; every failure
 * after the vendor call starts attempts to restore the captured settings.
 */
object OnePlusUidEmulation {

    private const val VENDOR_ADAPTER_CLASS = "com.vendor.nfc.VendorNfcAdapter"
    private const val PREFS = "oneplus_uid_emulation"
    private const val PREF_PENDING = "pending"
    private const val PREF_MODE = "mode"
    private const val PREF_ATQA = "atqa"
    private const val PREF_SAK = "sak"
    private const val PREF_UID = "uid"
    private const val PREF_FINGERPRINT = "fingerprint"

    private const val SETTING_MODE = "hce_access_card_mode"
    private const val SETTING_ATQA = "hce_access_card_atqa"
    private const val SETTING_SAK = "hce_access_card_sak"
    private const val SETTING_UID = "hce_access_card_uid"

    private val CTS_ATQA = byteArrayOf(0x06, 0x0C)
    private val CTS_SAK = byteArrayOf(0x20)
    private const val NULL_VALUE = "\u0000"

    data class Probe(
        val available: Boolean,
        val reason: String? = null,
        val secureSettingsGranted: Boolean = false,
        val pendingRestore: Boolean = false,
    )

    private data class Api(
        val adapter: Any,
        val setter: Method,
    )

    private data class Snapshot(
        val mode: String?,
        val atqa: String?,
        val sak: String?,
        val uid: String?,
        val fingerprint: String,
    )

    fun probe(context: Context): Probe {
        val granted = canWriteSecureSettings(context)
        val pending = hasPendingRestore(context)
        if (!"oneplus".equals(Build.MANUFACTURER, ignoreCase = true)) {
            return Probe(
                available = false,
                reason = "实验性 OnePlus 通道仅适用于 OnePlus 设备",
                secureSettingsGranted = granted,
                pendingRestore = pending,
            )
        }
        return try {
            resolveApi(context)
            Probe(
                available = true,
                secureSettingsGranted = granted,
                pendingRestore = pending,
            )
        } catch (failure: Throwable) {
            // If the library and methods exist, the vendor service may simply
            // be blocked until WRITE_SECURE_SETTINGS is granted. Keep the path
            // available so the settings page can show the ADB command.
            if (hasVendorApiShape(context)) {
                Probe(
                    available = true,
                    reason = describe(failure),
                    secureSettingsGranted = granted,
                    pendingRestore = pending,
                )
            } else {
                Probe(
                    available = false,
                    reason = describe(failure),
                    secureSettingsGranted = granted,
                    pendingRestore = pending,
                )
            }
        }
    }

    private fun hasVendorApiShape(context: Context): Boolean = runCatching {
        val adapterClass = Class.forName(VENDOR_ADAPTER_CLASS, false, context.classLoader)
        adapterClass.getMethod("getVendorNfcAdapter")
        adapterClass.getMethod(
            "setHceTypeAConfig",
            Boolean::class.javaPrimitiveType,
            ByteArray::class.java,
            ByteArray::class.java,
            ByteArray::class.java,
        )
    }.isSuccess

    fun activate(context: Context, uid: ByteArray): UidEmulation.Outcome {
        if (uid.size !in UidCodec.supportedByteLengths) {
            return UidEmulation.Outcome.Failure("UID 必须为 4、7 或 10 字节")
        }
        if (!canWriteSecureSettings(context)) {
            return UidEmulation.Outcome.Failure(
                "需要 WRITE_SECURE_SETTINGS：adb shell pm grant " +
                    context.packageName + " android.permission.WRITE_SECURE_SETTINGS"
            )
        }
        val api = try {
            resolveApi(context)
        } catch (failure: Throwable) {
            return UidEmulation.Outcome.Failure(describe(failure))
        }

        val existing = loadSnapshot(context)
        val snapshot = existing ?: captureSnapshot(context)
        val capturedNow = existing == null
        validate(snapshot)?.let { problem ->
            return UidEmulation.Outcome.Failure("原始 HCE 配置不可用，已取消：$problem")
        }
        if (capturedNow && !persistSnapshot(context, snapshot)) {
            return UidEmulation.Outcome.Failure("无法保存原始 HCE 配置，已取消")
        }

        var vendorCallAttempted = false
        return try {
            vendorCallAttempted = true
            if (!invoke(api, true, CTS_ATQA, CTS_SAK, uid)) {
                if (capturedNow) clearSnapshot(context)
                return UidEmulation.Outcome.Failure("OnePlus 控制器拒绝了 UID 配置")
            }
            if (!writeSetting(context, SETTING_MODE, "0")) {
                throw IllegalStateException("无法关闭 OnePlus HCE RF guard")
            }
            if (!restoreMetadata(context, snapshot)) {
                throw IllegalStateException("无法恢复原始 HCE 元数据")
            }
            UidEmulation.Outcome.Success
        } catch (failure: Throwable) {
            if (!vendorCallAttempted && capturedNow) {
                clearSnapshot(context)
            } else {
                runCatching { restore(context, api, snapshot) }
            }
            UidEmulation.Outcome.Failure(describe(failure))
        }
    }

    fun deactivate(context: Context): UidEmulation.Outcome {
        val snapshot = loadSnapshot(context) ?: return UidEmulation.Outcome.Success
        val api = try {
            resolveApi(context)
        } catch (failure: Throwable) {
            return UidEmulation.Outcome.Failure(
                "存在未完成的模拟状态，但当前无法恢复：" + describe(failure)
            )
        }
        return try {
            restore(context, api, snapshot)
            if (!clearSnapshot(context)) {
                UidEmulation.Outcome.Failure("原始配置已恢复，但待恢复标记未能清除")
            } else {
                UidEmulation.Outcome.Success
            }
        } catch (failure: Throwable) {
            UidEmulation.Outcome.Failure(describe(failure))
        }
    }

    fun hasPendingRestore(context: Context): Boolean =
        preferences(context).getBoolean(PREF_PENDING, false)

    private fun restore(context: Context, api: Api, snapshot: Snapshot) {
        validate(snapshot)?.let { problem ->
            throw IllegalStateException("原始 HCE 配置不可用：$problem")
        }
        val accepted = if (snapshot.mode == "1") {
            invoke(
                api,
                true,
                parseHex(snapshot.atqa, intArrayOf(2))!!,
                parseHex(snapshot.sak, intArrayOf(1))!!,
                parseHex(snapshot.uid, UidCodec.supportedByteLengths)!!,
            )
        } else {
            invoke(api, false, null, null, null)
        }
        if (!accepted) {
            throw IllegalStateException("OnePlus 控制器拒绝了恢复请求")
        }
        if (!restoreAllSettings(context, snapshot)) {
            throw IllegalStateException("原始 HCE 设置未能完整恢复")
        }
    }

    private fun validate(snapshot: Snapshot): String? {
        if (snapshot.mode != "1") return null
        if (parseHex(snapshot.atqa, intArrayOf(2)) == null) return "ATQA 缺失或格式错误"
        if (parseHex(snapshot.sak, intArrayOf(1)) == null) return "SAK 缺失或格式错误"
        if (parseHex(snapshot.uid, UidCodec.supportedByteLengths) == null) {
            return "UID 缺失或长度不受支持"
        }
        return null
    }

    private fun resolveApi(context: Context): Api {
        val adapterClass = try {
            Class.forName(VENDOR_ADAPTER_CLASS, false, context.classLoader)
        } catch (failure: Throwable) {
            throw UnsupportedOperationException("设备未提供 OnePlus nfcvendorlib", failure)
        }
        val getter = try {
            adapterClass.getMethod("getVendorNfcAdapter")
        } catch (failure: Throwable) {
            throw UnsupportedOperationException("nfcvendorlib 缺少 getVendorNfcAdapter", failure)
        }
        val setter = try {
            adapterClass.getMethod(
                "setHceTypeAConfig",
                Boolean::class.javaPrimitiveType,
                ByteArray::class.java,
                ByteArray::class.java,
                ByteArray::class.java,
            )
        } catch (failure: Throwable) {
            throw UnsupportedOperationException("nfcvendorlib 缺少 setHceTypeAConfig", failure)
        }
        val adapter = try {
            getter.invoke(null)
        } catch (failure: Throwable) {
            throw IllegalStateException("无法访问 OnePlus vendor NFC 服务：" + describe(failure))
        } ?: throw IllegalStateException("OnePlus vendor NFC 服务不可用")
        return Api(adapter, setter)
    }

    private fun invoke(
        api: Api,
        enable: Boolean,
        atqa: ByteArray?,
        sak: ByteArray?,
        uid: ByteArray?,
    ): Boolean {
        val result = try {
            api.setter.invoke(api.adapter, enable, atqa, sak, uid)
        } catch (failure: InvocationTargetException) {
            throw IllegalStateException(describe(failure), failure)
        }
        return result as? Boolean
            ?: throw IllegalStateException("OnePlus vendor 返回了非布尔结果：$result")
    }

    private fun captureSnapshot(context: Context): Snapshot = Snapshot(
        mode = readSetting(context, SETTING_MODE),
        atqa = readSetting(context, SETTING_ATQA),
        sak = readSetting(context, SETTING_SAK),
        uid = readSetting(context, SETTING_UID),
        fingerprint = Build.FINGERPRINT,
    )

    private fun persistSnapshot(context: Context, snapshot: Snapshot): Boolean =
        preferences(context).edit()
            .putBoolean(PREF_PENDING, true)
            .putString(PREF_MODE, encode(snapshot.mode))
            .putString(PREF_ATQA, encode(snapshot.atqa))
            .putString(PREF_SAK, encode(snapshot.sak))
            .putString(PREF_UID, encode(snapshot.uid))
            .putString(PREF_FINGERPRINT, snapshot.fingerprint)
            .commit()

    private fun loadSnapshot(context: Context): Snapshot? {
        val prefs = preferences(context)
        if (!prefs.getBoolean(PREF_PENDING, false)) return null
        return Snapshot(
            mode = decode(prefs.getString(PREF_MODE, NULL_VALUE)),
            atqa = decode(prefs.getString(PREF_ATQA, NULL_VALUE)),
            sak = decode(prefs.getString(PREF_SAK, NULL_VALUE)),
            uid = decode(prefs.getString(PREF_UID, NULL_VALUE)),
            fingerprint = prefs.getString(PREF_FINGERPRINT, "").orEmpty(),
        )
    }

    private fun clearSnapshot(context: Context): Boolean =
        preferences(context).edit().clear().commit()

    private fun restoreMetadata(context: Context, snapshot: Snapshot): Boolean {
        val atqa = writeSetting(context, SETTING_ATQA, snapshot.atqa)
        val sak = writeSetting(context, SETTING_SAK, snapshot.sak)
        val uid = writeSetting(context, SETTING_UID, snapshot.uid)
        return atqa && sak && uid
    }

    private fun restoreAllSettings(context: Context, snapshot: Snapshot): Boolean =
        restoreMetadata(context, snapshot) &&
            writeSetting(context, SETTING_MODE, snapshot.mode)

    private fun readSetting(context: Context, name: String): String? =
        runCatching { Settings.Global.getString(context.contentResolver, name) }.getOrNull()

    private fun writeSetting(context: Context, name: String, value: String?): Boolean =
        runCatching { Settings.Global.putString(context.contentResolver, name, value) }
            .getOrDefault(false)

    private fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encode(value: String?): String = value ?: NULL_VALUE

    private fun decode(value: String?): String? =
        if (value == null || value == NULL_VALUE) null else value

    private fun parseHex(value: String?, sizes: IntArray): ByteArray? {
        if (value.isNullOrBlank()) return null
        val clean = value.filterNot { it.isWhitespace() || it == ':' || it == '-' }
        if (clean.length % 2 != 0) return null
        if (clean.length / 2 !in sizes) return null
        if (!clean.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun describe(failure: Throwable): String {
        var root = failure
        while (root is InvocationTargetException && root.cause != null) {
            root = root.cause!!
        }
        val message = root.message
        return root.javaClass.simpleName + if (message.isNullOrBlank()) "" else "：$message"
    }
}
