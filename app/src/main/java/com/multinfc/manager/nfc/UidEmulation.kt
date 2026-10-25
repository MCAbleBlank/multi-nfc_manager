package com.multinfc.manager.nfc

import android.content.Context
import android.nfc.NfcAdapter
import android.os.Build
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Experimental NFC-A UID emulation through Android's OEM extension API.
 *
 * The only path implemented here is the platform API
 * `NfcOemExtension.emulateNfcTechnologyATag(...)`, first shipped with
 * Android 16 QPR2 (API 36.1) and only present when the OEM keeps the
 * extension in its NFC module. It changes the UID the controller presents
 * while a tag is emulated; card memory, MIFARE keys, authentication and
 * application data are *not* reproduced.
 *
 * Raw NCI and vendor HCE paths (as used by Emula for older devices) are
 * deliberately not implemented: they require `WRITE_SECURE_SETTINGS`,
 * hidden NCI callbacks and firmware-specific snapshots, and writing an
 * unverified controller configuration can leave NFC in a broken state.
 *
 * Parameter profiles and status codes follow AOSP
 * `packages/modules/Nfc/.../NfcOemExtension.java` and the behaviour observed
 * by the GPL-3.0 Emula project; no Emula source code is copied here.
 *
 * The API is hidden from the SDK, so it is reached through reflection. Call
 * [setEnabled] and [disable] off the main thread.
 */
object UidEmulation {

    data class Capability(
        val supported: Boolean,
        val reason: String? = null,
    )

    sealed interface Outcome {
        data object Success : Outcome
        data object NfcDisabled : Outcome
        data object Rejected : Outcome
        data class Failure(val message: String) : Outcome
    }

    private data class Api(
        val adapter: NfcAdapter,
        val extension: Any,
        val emulateMethod: Method,
    )

    private class Profile(
        val name: String,
        val bitFrameSdd: Byte,
        val platformConfig: Byte,
        val selInfo: Byte,
        val rats: Byte,
    )

    /** Google's NFC stack needs the minimal SENS_RES to reach activation. */
    private val PIXEL_PROFILE = Profile(
        name = "pixel-minimal",
        bitFrameSdd = 0x04.toByte(),
        platformConfig = 0x00.toByte(),
        selInfo = 0x00.toByte(),
        rats = 0x00.toByte(),
    )

    /**
     * Android's reference Type 4A values. Used for every other OEM because
     * they are the configuration the CTS exercises.
     */
    private val CTS_TYPE_4A_PROFILE = Profile(
        name = "cts-type-4a",
        bitFrameSdd = 0x06.toByte(),
        platformConfig = 0x0C.toByte(),
        selInfo = 0x20.toByte(),
        rats = 0x40.toByte(),
    )

    private val EMULATE_PARAMETERS: Array<Class<*>> = arrayOf(
        Boolean::class.javaPrimitiveType!!,
        Byte::class.javaPrimitiveType!!,
        Byte::class.javaPrimitiveType!!,
        Byte::class.javaPrimitiveType!!,
        ByteArray::class.java,
        Byte::class.javaPrimitiveType!!,
        ByteArray::class.java,
    )

    /** Checks the API itself; an OEM may backport it without changing SDK_INT. */
    fun inspect(context: Context): Capability = try {
        resolveApi(context)
        Capability(supported = true)
    } catch (failure: UnsupportedOperationException) {
        Capability(supported = false, reason = failure.message)
    } catch (failure: Throwable) {
        Capability(supported = false, reason = "无法访问 NFC OEM 扩展：${describe(failure)}")
    }

    /**
     * Enables or disables UID emulation. [uid] must contain exactly 4, 7 or
     * 10 bytes. Disabling ignores the UID content but still needs a
     * well-formed array, so callers should use [disable] instead.
     */
    fun setEnabled(context: Context, uid: ByteArray, enable: Boolean): Outcome {
        if (uid.size !in UidCodec.supportedByteLengths) {
            return Outcome.Failure("UID 必须为 4、7 或 10 字节")
        }

        val api = try {
            resolveApi(context)
        } catch (failure: UnsupportedOperationException) {
            return Outcome.Failure(failure.message ?: "当前设备不支持 NFC-A UID 模拟")
        } catch (failure: Throwable) {
            return Outcome.Failure(describe(failure))
        }

        if (!api.adapter.isEnabled) return Outcome.NfcDisabled

        val profile = profileForDevice()
        return try {
            val result = invoke(
                api.extension,
                api.emulateMethod,
                enable,
                profile.bitFrameSdd,
                profile.platformConfig,
                profile.selInfo,
                uid.copyOf(),
                profile.rats,
                null,
            )
            when (result as? Int) {
                null -> Outcome.Failure("NFC OEM 扩展返回了非整型结果")
                0 -> Outcome.Success
                1 -> Outcome.NfcDisabled
                2 -> Outcome.Rejected
                else -> Outcome.Failure("NFC OEM 扩展返回未知状态 $result")
            }
        } catch (failure: Throwable) {
            Outcome.Failure(describe(failure))
        }
    }

    /**
     * Restores the controller's default configuration. The platform expects a
     * disabled request with a 4-byte placeholder UID.
     */
    fun disable(context: Context): Outcome = setEnabled(context, ByteArray(4), enable = false)

    private fun profileForDevice(): Profile =
        if ("google".equals(Build.MANUFACTURER, ignoreCase = true)) {
            PIXEL_PROFILE
        } else {
            CTS_TYPE_4A_PROFILE
        }

    private fun resolveApi(context: Context): Api {
        val adapter = NfcAdapter.getDefaultAdapter(context)
            ?: throw UnsupportedOperationException("设备没有可用的 NFC 适配器")

        val extension = try {
            val getter = findMethod(adapter.javaClass, "getNfcOemExtension", emptyArray())
            invoke(adapter, getter) ?: throw UnsupportedOperationException(
                "系统未启用 NFC OEM 扩展"
            )
        } catch (failure: NoSuchMethodException) {
            throw UnsupportedOperationException(
                "当前系统未提供 NFC OEM 扩展（需要 Android 16 QPR2+ / API 36.1，且厂商未裁剪）",
                failure,
            )
        }

        val emulateMethod = try {
            findMethod(extension.javaClass, "emulateNfcTechnologyATag", EMULATE_PARAMETERS)
        } catch (failure: NoSuchMethodException) {
            throw UnsupportedOperationException(
                "NFC OEM 扩展缺少 emulateNfcTechnologyATag",
                failure,
            )
        }
        return Api(adapter, extension, emulateMethod)
    }

    private fun findMethod(
        owner: Class<*>,
        name: String,
        parameterTypes: Array<Class<*>>,
    ): Method = try {
        owner.getMethod(name, *parameterTypes)
    } catch (failure: NoSuchMethodException) {
        owner.getDeclaredMethod(name, *parameterTypes)
    }

    private fun invoke(receiver: Any, method: Method, vararg arguments: Any?): Any? {
        try {
            method.isAccessible = true
        } catch (_: Throwable) {
            // Public system APIs may be reachable without bypassing accessibility.
        }
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
}
