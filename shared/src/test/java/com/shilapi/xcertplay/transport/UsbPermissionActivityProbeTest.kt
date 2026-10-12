package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [PermissionRequestUsbManagerShadow::class])
class UsbPermissionActivityProbeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(UsbPermissionActivityProbe.PREFS, Context.MODE_PRIVATE)
    private val component = ComponentName.unflattenFromString(UsbPermissionActivityProbe.COMPONENT)!!

    @Before fun reset() {
        prefs.edit().clear().commit()
        shadowOf(context.packageManager).removeActivity(component)
        PermissionRequestUsbManagerShadow.requests = 0
    }

    private fun installPermissionActivity() {
        val info = shadowOf(context.packageManager).addActivityIfNotPresent(component)
        info.enabled = true
        info.exported = false // system_server can open it even when apps cannot.
        info.applicationInfo.enabled = true
        shadowOf(context.packageManager).addOrUpdateActivity(info)
    }

    @Test fun checksTheActualActivityAndItsEnabledState() {
        assertEquals(UsbPermissionActivityProbe.State.UNAVAILABLE, UsbPermissionActivityProbe.probe(context.packageManager))
        installPermissionActivity()
        assertEquals(UsbPermissionActivityProbe.State.AVAILABLE, UsbPermissionActivityProbe.probe(context.packageManager))
        context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
        assertEquals(UsbPermissionActivityProbe.State.UNAVAILABLE, UsbPermissionActivityProbe.probe(context.packageManager))
    }

    @Test fun aDisabledSystemUiApplicationMakesTheActivityUnavailable() {
        installPermissionActivity()
        context.packageManager.setApplicationEnabledSetting(component.packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
        assertEquals(UsbPermissionActivityProbe.State.UNAVAILABLE, UsbPermissionActivityProbe.probe(context.packageManager))
    }

    @Test fun confirmedResultsPersistWithoutTimeBasedExpiry() {
        for (state in listOf(UsbPermissionActivityProbe.State.AVAILABLE, UsbPermissionActivityProbe.State.UNAVAILABLE)) {
            prefs.edit().clear().commit()
            assertEquals(state, UsbPermissionActivityProbe.cached(prefs, "firmware:app-v1") { state }.state)
            val persisted = context.getSharedPreferences(UsbPermissionActivityProbe.PREFS, Context.MODE_PRIVATE)
            // A timestamp from the previous cache format must no longer make a result expire.
            persisted.edit().putLong("checkedAt", 1).commit()
            val next = UsbPermissionActivityProbe.cached(persisted, "firmware:app-v1") { fail("cache should avoid probing"); state }
            assertEquals(state, next.state)
            assertTrue(next.cached)
        }
    }

    @Test fun firmwareOrAppUpdateInvalidatesTheCache() {
        for (identity in listOf("firmware-v2:app-v1", "firmware-v1:app-v2")) {
            UsbPermissionActivityProbe.cached(prefs, "firmware-v1:app-v1") { UsbPermissionActivityProbe.State.UNAVAILABLE }
            val result = UsbPermissionActivityProbe.cached(prefs, identity) { UsbPermissionActivityProbe.State.AVAILABLE }
            assertFalse(result.cached)
            assertEquals(UsbPermissionActivityProbe.State.AVAILABLE, result.state)
        }
    }

    @Test fun inconclusiveResultsAreNotCached() {
        repeat(2) {
            val result = UsbPermissionActivityProbe.cached(prefs, "firmware:app") { UsbPermissionActivityProbe.State.UNKNOWN }
            assertFalse(result.cached)
            assertEquals(UsbPermissionActivityProbe.State.UNKNOWN, result.state)
        }
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun s202RequestsAdbFirstWithoutProbingOrLaunchingTheSystemUi() {
        ReflectionHelpers.setStaticField(Build::class.java, "BOARD", "spm8666p1_64_car")
        ReflectionHelpers.setStaticField(Build::class.java, "HARDWARE", "mt8666")
        val usb = context.getSystemService(UsbManager::class.java)
        val diagnostics = mutableListOf<String>()
        for (name in listOf("initial", "reenumerated")) {
            val host = IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor(), onDiagnostic = diagnostics::add)
            assertTrue(host.requestPermission(device(name)) is IphoneUsbHost.PermissionRequest.AdbRequired)
        }
        assertEquals(0, PermissionRequestUsbManagerShadow.requests)
        assertTrue(diagnostics.isEmpty())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun failedAdbWithMissingUiSkipsTheSystemRequestAndCachesTheProbe() {
        val usb = context.getSystemService(UsbManager::class.java)
        val diagnostics = mutableListOf<String>()
        repeat(2) {
            val host = IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor(), onDiagnostic = diagnostics::add)
            assertTrue(host.requestPermission(device("initial"), preferAdbAuthorization = false) is IphoneUsbHost.PermissionRequest.UiUnavailable)
        }
        assertEquals(0, PermissionRequestUsbManagerShadow.requests)
        assertTrue(diagnostics[0].contains("state=UNAVAILABLE cached=false"))
        assertTrue(diagnostics[1].contains("state=UNAVAILABLE cached=true"))
    }

    @Test fun failedAdbWithAvailableUiFallsBackToTheSystemDialog() {
        installPermissionActivity()
        val usb = context.getSystemService(UsbManager::class.java)
        val host = IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor())
        assertTrue(host.requestPermission(device("initial"), preferAdbAuthorization = false) is IphoneUsbHost.PermissionRequest.Requested)
        assertEquals(1, PermissionRequestUsbManagerShadow.requests)
    }

    @Test fun otherHardwarePreservesTheSystemPermissionFlowWithoutProbing() {
        ReflectionHelpers.setStaticField(Build::class.java, "BOARD", "other")
        val usb = context.getSystemService(UsbManager::class.java)
        val diagnostics = mutableListOf<String>()
        val host = IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor(), onDiagnostic = diagnostics::add)
        assertTrue(host.requestPermission(device("initial")) is IphoneUsbHost.PermissionRequest.Requested)
        assertEquals(1, PermissionRequestUsbManagerShadow.requests)
        assertTrue(diagnostics.isEmpty())
    }

    @Test fun existingDevicePermissionDoesNotProbeOrRequestAdbOrTheUi() {
        val usb = context.getSystemService(UsbManager::class.java)
        val device = device("granted")
        shadowOf(usb).addOrUpdateUsbDevice(device, true)
        val diagnostics = mutableListOf<String>()
        val host = IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor(), onDiagnostic = diagnostics::add)
        assertTrue(host.requestPermission(device) is IphoneUsbHost.PermissionRequest.AlreadyGranted)
        assertTrue(host.requestPermission(device, preferAdbAuthorization = false) is IphoneUsbHost.PermissionRequest.AlreadyGranted)
        assertEquals(0, PermissionRequestUsbManagerShadow.requests)
        assertTrue(diagnostics.isEmpty())
        assertTrue(prefs.all.isEmpty())
    }

    private fun device(name: String): UsbDevice = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
        ReflectionHelpers.setField(this, "mName", name)
        ReflectionHelpers.setField(this, "mVendorId", IphoneUsbMatcher.APPLE_VENDOR_ID)
        ReflectionHelpers.setField(this, "mProductId", 0x12a8)
    }
}

@Implements(UsbManager::class)
class PermissionRequestUsbManagerShadow : ShadowUsbManager() {
    companion object { var requests = 0 }

    @Implementation
    protected fun requestPermission(device: UsbDevice, result: PendingIntent) { requests++ }
}
