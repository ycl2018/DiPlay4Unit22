package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavigationChannelVolumeTestTest {
    @After fun stopPreview() = NavigationChannelVolumeTest.stop()

    @Test fun previewPublishesOnlyTheCurrentlyTestedChannel() {
        assertFalse(NavigationChannelVolumeTest.snapshot().active)

        NavigationChannelVolumeTest.start(7)
        assertEquals(NavigationChannelVolumeTest.Snapshot(true, 7), NavigationChannelVolumeTest.snapshot())

        NavigationChannelVolumeTest.start(10)
        assertEquals(NavigationChannelVolumeTest.Snapshot(true, 10), NavigationChannelVolumeTest.snapshot())

        NavigationChannelVolumeTest.stop()
        assertFalse(NavigationChannelVolumeTest.snapshot().active)
    }
}
