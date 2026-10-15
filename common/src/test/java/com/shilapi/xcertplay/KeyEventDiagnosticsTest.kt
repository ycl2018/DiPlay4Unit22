package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.media.AudioManager
import android.view.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeyEventDiagnosticsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() = KeyEventDiagnostics.clear(context)
    @After fun tearDown() {
        NavigationChannelVolumeTest.stop()
        KeyEventDiagnostics.clear(context)
    }

    @Test fun recordsPhysicalIdentityStateDecisionAndReadableKeyName() {
        val event = KeyEvent(100, 120, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP,
            2, 0, 17, 115)

        KeyEventDiagnostics.record(context, "host", event, consumed = false, handler = "system",
            nowMillis = 1_700_000_000_123L)

        val report = KeyEventDiagnostics.report(context)
        assertTrue(report.contains(".123 KEY_EVENT"))
        assertTrue(report.contains("source=host action=DOWN"))
        assertTrue(report.contains("keyCode=24("))
        assertTrue(report.contains("scanCode=115 deviceId=17 repeat=2"))
        assertTrue(report.contains("sessionActive=false"))
        assertTrue(report.contains("navigationChannelTest=false"))
        assertTrue(report.contains("legacyStream=none"))
        assertTrue(report.contains("consumed=false handler=system"))
        assertEquals(report, DiagnosticRedactor.redact(report))
    }

    @Test fun marksTemporaryNavigationChannelTestsExplicitly() {
        NavigationChannelVolumeTest.start(7)

        KeyEventDiagnostics.record(
            context,
            "accessibility",
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP),
            consumed = false,
            handler = "vehicle",
            nowMillis = 1_700_000_000_456L,
        )

        val report = KeyEventDiagnostics.report(context)
        assertTrue(report.contains("navigationChannelTest=true"))
        assertTrue(report.contains("legacyStream=7"))
    }

    @Test fun keepsOnlyTheNewestBoundedTail() {
        repeat(KeyEventDiagnostics.MAX_EVENTS + 5) { index ->
            KeyEventDiagnostics.record(
                context,
                "accessibility",
                KeyEvent(0, index.toLong(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F1,
                    0, 0, 3, index),
                consumed = true,
                handler = "entry-$index",
                nowMillis = index.toLong(),
            )
            assertTrue(KeyEventDiagnostics.awaitIdle(2_000))
        }

        val lines = KeyEventDiagnostics.report(context).lines()
        assertEquals(KeyEventDiagnostics.MAX_EVENTS, lines.size)
        assertTrue(lines.first().contains("scanCode=5"))
        assertTrue(lines.first().contains("handler=entry-5"))
        assertTrue(lines.last().contains("scanCode=84"))
        assertTrue(lines.last().contains("handler=entry-84"))
    }

    @Test fun emptyReportIsExplicit() {
        assertEquals("No physical key events recorded yet.", KeyEventDiagnostics.report(context))
    }

    @Test fun queuedEventRetainsTheCallStateAtKeyDispatch() {
        val storageEntered = CountDownLatch(1)
        val resumeStorage = CountDownLatch(1)
        // Hold the real writer in storage so the second event is formatted after the call starts.
        val blockedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name == "diplay_key_event_diagnostics") {
                    storageEntered.countDown()
                    check(resumeStorage.await(3, TimeUnit.SECONDS))
                }
                return super.getSharedPreferences(name, mode)
            }
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
        try {
            KeyEventDiagnostics.record(blockedContext, "host",
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1), false, "blocker")
            assertTrue(storageEntered.await(2, TimeUnit.SECONDS))
            KeyEventDiagnostics.record(context, "host",
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F2), false, "before-call", nowMillis = 1_000)
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            resumeStorage.countDown()

            val line = KeyEventDiagnostics.report(context).lines().single { it.contains("handler=before-call") }
            assertTrue(line, line.contains("inCall=false"))
            assertTrue(line, line.contains("consumed=false"))
        } finally {
            resumeStorage.countDown()
            audio.mode = AudioManager.MODE_NORMAL
        }
    }
}
