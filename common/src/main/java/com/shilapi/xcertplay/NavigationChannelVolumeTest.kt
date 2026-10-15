package com.shilapi.xcertplay

/** Temporary route advertised only while the channel-search tone is on screen. */
internal object NavigationChannelVolumeTest {
    data class Snapshot(val active: Boolean, val channel: Int?)

    @Volatile private var activeChannel: Int? = null

    fun start(channel: Int) {
        require(channel in NAVIGATION_VOLUME_SEARCH_CHANNELS)
        activeChannel = channel
    }

    fun stop() {
        activeChannel = null
    }

    fun snapshot(): Snapshot = activeChannel?.let { Snapshot(true, it) } ?: Snapshot(false, null)
}
