package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IphoneUsbAdbGrantTest {
    @Test fun fallbackIsRestrictedToAndroidNineOnTheReferenceS202() {
        assertTrue(IphoneUsbAdbGrant.supported(28, "spm8666p1_64_car", "mt8666"))
        assertFalse(IphoneUsbAdbGrant.supported(27, "spm8666p1_64_car", "mt8666"))
        assertFalse(IphoneUsbAdbGrant.supported(28, "other", "mt8666"))
        assertFalse(IphoneUsbAdbGrant.supported(28, "spm8666p1_64_car", "other"))
    }

    @Test fun commandQuotesTheApkAndUsesTheInstalledPackage() {
        assertEquals(
            "CLASSPATH='/data/app/it'\"'\"'s/base.apk' app_process /system/bin " +
                "com.shilapi.xcertplay.transport.IphoneUsbGrantTool com.shihab.diplay",
            IphoneUsbAdbGrant.command("/data/app/it's/base.apk", "com.shihab.diplay"),
        )
    }

    @Test fun parsesBoundedHelperResults() {
        val ready = IphoneUsbAdbGrant.parse("DIPLAY_USB_GRANT_V1|READY|1|1|1")
        assertEquals(IphoneUsbAdbGrant.Code.READY, ready.code)
        assertTrue(ready.permissionGranted)
        assertEquals(1, ready.defaults)

        val temporary = IphoneUsbAdbGrant.parse("noise\nDIPLAY_USB_GRANT_V1|TEMPORARY|2|1|0")
        assertEquals(IphoneUsbAdbGrant.Code.TEMPORARY, temporary.code)
        assertTrue(temporary.permissionGranted)

        for (invalid in listOf(
            null,
            "DIPLAY_USB_GRANT_V1|READY|1|0|1",
            "DIPLAY_USB_GRANT_V1|READY|1|2|1",
            "DIPLAY_USB_GRANT_V1|UNKNOWN|1|1|1",
            "DIPLAY_USB_GRANT_V1|READY|-1|0|0",
        )) {
            assertEquals(IphoneUsbAdbGrant.Code.ERROR, IphoneUsbAdbGrant.parse(invalid).code)
        }
    }
}
