package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.media.NavigationPlayback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small persistent tail of physical-key routing decisions for exported diagnostics. */
internal object KeyEventDiagnostics {
    private const val PREFS = "diplay_key_event_diagnostics"
    private const val KEY_EVENTS = "events"
    internal const val MAX_EVENTS = 80

    private data class Entry(
        val context: Context,
        val source: String,
        val action: Int,
        val keyCode: Int,
        val scanCode: Int,
        val deviceId: Int,
        val repeatCount: Int,
        val consumed: Boolean,
        val handler: String,
        val nowMillis: Long,
        val state: State?,
    )

    private data class State(
        val sessionActive: Boolean,
        val guidanceActive: Boolean,
        val navigationAudioActive: Boolean,
        val legacyStream: Int?,
        val mapEligible: Boolean,
        val navigationEligible: Boolean,
        val calling: Boolean,
    )

    private val storageLock = Any()
    private val writer = BoundedDiagnosticWriter<Entry>(maxPending = 64, write = ::write)

    /** Captures event and runtime snapshots together; formatting and storage stay off the key thread. */
    fun record(
        context: Context,
        source: String,
        event: KeyEvent,
        consumed: Boolean,
        handler: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        writer.enqueue(Entry(
            context = context.applicationContext,
            source = source,
            action = event.action,
            keyCode = event.keyCode,
            scanCode = event.scanCode,
            deviceId = event.deviceId,
            repeatCount = event.repeatCount,
            consumed = consumed,
            handler = handler,
            nowMillis = nowMillis,
            state = runCatching { captureState(context) }.getOrNull(),
        ))
    }

    private fun write(entry: Entry) {
        val line = runCatching { detailedLine(entry) }.getOrElse {
            // Diagnostics must survive an optional runtime-state probe failing on vendor firmware.
            "${entry.nowMillis} KEY_EVENT source=${entry.source} action=${actionName(entry.action)} " +
                "keyCode=${entry.keyCode} scanCode=${entry.scanCode} deviceId=${entry.deviceId} " +
                "repeat=${entry.repeatCount} state=unavailable consumed=${entry.consumed} handler=${entry.handler}"
        }
        val safe = DiagnosticRedactor.redact(line) ?: return
        synchronized(storageLock) {
            val prefs = entry.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = prefs.getString(KEY_EVENTS, null).orEmpty().lineSequence()
                .filter(String::isNotBlank).toList()
            prefs.edit().putString(KEY_EVENTS, (previous + safe).takeLast(MAX_EVENTS).joinToString("\n")).apply()
        }
    }

    private fun captureState(context: Context): State {
        val background = CarPlayBackgroundSession.snapshot()
        val controller = background?.controller
        val sessionActive = controller?.activeAirPlaySessionToken() != null
        val playback = NavigationPlayback.snapshot()
        val calling = inCall(context)
        val legacyStream = playback.legacyStreamType
        val navigationEligible = NavigationWheelSettings.enabled(context) && sessionActive && !calling &&
            playback.active && !playback.priorityVoiceActive &&
            legacyStream != null && legacyStream in 1..20 && legacyStream != AudioManager.STREAM_MUSIC
        return State(
            sessionActive = sessionActive,
            guidanceActive = CarPlayGlance.snapshot().maneuverType != null,
            navigationAudioActive = playback.active,
            legacyStream = legacyStream,
            mapEligible = controller?.dashboardMapStreaming() == true,
            navigationEligible = navigationEligible,
            calling = calling,
        )
    }

    private fun detailedLine(entry: Entry): String {
        return buildString {
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(entry.nowMillis)))
            append(" KEY_EVENT source=").append(entry.source)
            append(" action=").append(actionName(entry.action))
            append(" keyCode=").append(entry.keyCode)
            append('(').append(keyName(entry.keyCode)).append(')')
            append(" scanCode=").append(entry.scanCode)
            append(" deviceId=").append(entry.deviceId)
            append(" repeat=").append(entry.repeatCount)
            val state = entry.state
            if (state == null) {
                append(" state=unavailable")
            } else {
                append(" sessionActive=").append(state.sessionActive)
                append(" guidanceActive=").append(state.guidanceActive)
                append(" navigationAudioActive=").append(state.navigationAudioActive)
                append(" legacyStream=").append(state.legacyStream ?: "none")
                append(" mapEligible=").append(state.mapEligible)
                append(" navigationEligible=").append(state.navigationEligible)
                append(" inCall=").append(state.calling)
            }
            append(" consumed=").append(entry.consumed)
            append(" handler=").append(entry.handler)
        }
    }

    fun report(context: Context): String {
        // Report generation runs off the UI/input path. Include events queued immediately before export.
        writer.awaitIdle(REPORT_FLUSH_MILLIS)
        return synchronized(storageLock) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_EVENTS, null)
                ?.takeIf(String::isNotBlank)
                ?: "No physical key events recorded yet."
        }
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)

    internal fun clear(context: Context) {
        writer.awaitIdle(REPORT_FLUSH_MILLIS)
        synchronized(storageLock) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Suppress("DEPRECATION")
    private fun actionName(action: Int): String = when (action) {
        KeyEvent.ACTION_DOWN -> "DOWN"
        KeyEvent.ACTION_UP -> "UP"
        KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
        else -> action.toString()
    }

    private fun keyName(keyCode: Int): String = runCatching { KeyEvent.keyCodeToString(keyCode) }
        .getOrNull()?.takeIf(String::isNotBlank) ?: "KEYCODE_UNKNOWN"

    private const val REPORT_FLUSH_MILLIS = 2_000L
}
