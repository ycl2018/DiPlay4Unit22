package com.shilapi.xcertplay.transport

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.ChanganS202HeadUnit
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/** S202-only fallback when the OEM removed Android's USB permission activity. */
internal object IphoneUsbAdbGrant {
    internal const val HEADER = "DIPLAY_USB_GRANT_V1"
    enum class Code { READY, TEMPORARY, NO_DEVICE, ADB_UNAVAILABLE, UNSUPPORTED, DENIED, ERROR }

    data class Result(
        val code: Code,
        val attached: Int = 0,
        val granted: Int = 0,
        val defaults: Int = 0,
    ) {
        val permissionGranted: Boolean get() = granted > 0
    }

    fun supported(
        sdk: Int = Build.VERSION.SDK_INT,
        board: String? = Build.BOARD,
        hardware: String? = Build.HARDWARE,
    ): Boolean = sdk == Build.VERSION_CODES.P && ChanganS202HeadUnit.matches(board, hardware)

    fun grant(context: Context): Result {
        if (!supported()) return Result(Code.UNSUPPORTED)
        val app = context.applicationContext
        return LocalAdb(AdbKeys.load(app)).use { adb ->
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return@use Result(Code.ADB_UNAVAILABLE)
            parse(adb.shell(command(app.applicationInfo.sourceDir, app.packageName), 10_000))
        }
    }

    internal fun command(apk: String, packageName: String): String {
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")))
        val quotedApk = "'" + apk.replace("'", "'\"'\"'") + "'"
        return "CLASSPATH=$quotedApk app_process /system/bin ${IphoneUsbGrantTool::class.java.name} $packageName"
    }

    internal fun parse(output: String?): Result {
        val fields = output?.trim()?.lineSequence()?.lastOrNull()?.split('|') ?: return Result(Code.ERROR)
        if (fields.size != 5 || fields[0] != HEADER) return Result(Code.ERROR)
        val code = Code.entries.firstOrNull { it.name == fields[1] } ?: return Result(Code.ERROR)
        val counts = fields.drop(2).map { it.toIntOrNull() ?: return Result(Code.ERROR) }
        if (counts.any { it !in 0..16 } || counts[1] > counts[0] || counts[2] > counts[0]) return Result(Code.ERROR)
        if (code in setOf(Code.READY, Code.TEMPORARY) && counts[1] == 0) return Result(Code.ERROR)
        return Result(code, counts[0], counts[1], counts[2])
    }
}

/**
 * Runs under adb's shell UID through app_process. It grants only attached Apple devices to the
 * installed DiPlay package and records DiPlay as their default handler. Output is a bounded,
 * machine-readable summary and never includes device names, descriptors or exception text.
 */
object IphoneUsbGrantTool {
    private const val ROOT_UID = 0
    private const val SHELL_UID = 2000

    @JvmStatic
    fun main(args: Array<String>) {
        Thread({ Thread.sleep(12_000); exitProcess(2) }, "diplay-usb-grant-deadline")
            .apply { isDaemon = true; start() }
        val result = try {
            execute(args)
        } catch (error: Throwable) {
            val cause = unwrap(error)
            IphoneUsbAdbGrant.Result(
                if (cause is SecurityException) IphoneUsbAdbGrant.Code.DENIED else IphoneUsbAdbGrant.Code.ERROR,
            )
        }
        println(
            "${IphoneUsbAdbGrant.HEADER}|${result.code}|${result.attached}|" +
                "${result.granted}|${result.defaults}",
        )
        exitProcess(0)
    }

    @SuppressLint("PrivateApi")
    private fun execute(args: Array<String>): IphoneUsbAdbGrant.Result {
        if (Process.myUid() != SHELL_UID && Process.myUid() != ROOT_UID) {
            return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.DENIED)
        }
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.P || args.size != 1) {
            return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.UNSUPPORTED)
        }
        val packageName = args.single()
        if (!packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+"))) {
            return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.DENIED)
        }

        val context = systemContext()
        val app = context.packageManager.getApplicationInfo(packageName, 0)
        val userId = app.uid / 100_000
        val usb = context.getSystemService(UsbManager::class.java)
            ?: return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.UNSUPPORTED)
        val devices = usb.deviceList.values.filter { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID }.take(16)
        if (devices.isEmpty()) return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.NO_DEVICE)

        val service = usbService() ?: return IphoneUsbAdbGrant.Result(IphoneUsbAdbGrant.Code.UNSUPPORTED)
        val serviceType = Class.forName("android.hardware.usb.IUsbManager")
        val grant = serviceType.getMethod("grantDevicePermission", UsbDevice::class.java, Integer.TYPE)
        val setDefault = runCatching {
            serviceType.getMethod(
                "setDevicePackage",
                UsbDevice::class.java,
                String::class.java,
                Integer.TYPE,
            )
        }.getOrNull()

        var granted = 0
        var defaults = 0
        var denied = false
        for (device in devices) {
            try {
                invoke(grant, service, device, app.uid)
                granted++
            } catch (error: Throwable) {
                if (unwrap(error) is SecurityException) denied = true
            }
            if (setDefault != null) {
                try {
                    invoke(setDefault, service, device, packageName, userId)
                    defaults++
                } catch (error: Throwable) {
                    if (unwrap(error) is SecurityException) denied = true
                }
            }
        }
        val code = when {
            granted == 0 && denied -> IphoneUsbAdbGrant.Code.DENIED
            granted == 0 -> IphoneUsbAdbGrant.Code.ERROR
            defaults == devices.size -> IphoneUsbAdbGrant.Code.READY
            else -> IphoneUsbAdbGrant.Code.TEMPORARY
        }
        return IphoneUsbAdbGrant.Result(code, devices.size, granted, defaults)
    }

    @SuppressLint("PrivateApi")
    private fun systemContext(): Context {
        runCatching { android.os.Looper.prepareMainLooper() }
        val activityThread = Class.forName("android.app.ActivityThread")
        val main = activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(main) as Context
    }

    @SuppressLint("PrivateApi")
    private fun usbService(): Any? {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.USB_SERVICE) as? IBinder ?: return null
        val stub = Class.forName("android.hardware.usb.IUsbManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }

    private fun invoke(method: java.lang.reflect.Method, target: Any, vararg args: Any?) {
        try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is InvocationTargetException && error.targetException != null) error.targetException else error
}
