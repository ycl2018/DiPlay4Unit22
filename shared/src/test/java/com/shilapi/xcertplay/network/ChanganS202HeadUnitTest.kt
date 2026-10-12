package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChanganS202HeadUnitTest {
    @Test fun detectionRequiresBothMeasuredIdentifiers() {
        assertTrue(ChanganS202HeadUnit.matches("spm8666p1_64_car", "mt8666"))
        assertFalse(ChanganS202HeadUnit.matches("spm8666p1_64_car", "other"))
        assertFalse(ChanganS202HeadUnit.matches("other", "mt8666"))
        assertFalse(ChanganS202HeadUnit.matches(null, null))
    }
}
