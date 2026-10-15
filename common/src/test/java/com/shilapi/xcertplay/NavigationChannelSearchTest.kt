package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationChannelSearchTest {
    @Test fun adjacentChannelsMoveAndWrapAcrossIndependentVolumeCandidates() {
        assertEquals(2, adjacentNavigationVolumeChannel(1, 1))
        assertEquals(4, adjacentNavigationVolumeChannel(2, 1))
        assertEquals(2, adjacentNavigationVolumeChannel(4, -1))
        assertEquals(1, adjacentNavigationVolumeChannel(20, 1))
        assertEquals(20, adjacentNavigationVolumeChannel(1, -1))
    }

    @Test fun anOutOfRangeStartingValueIsSanitizedBeforeMoving() {
        assertEquals(2, adjacentNavigationVolumeChannel(0, 1))
        assertEquals(20, adjacentNavigationVolumeChannel(3, -1))
    }
}
