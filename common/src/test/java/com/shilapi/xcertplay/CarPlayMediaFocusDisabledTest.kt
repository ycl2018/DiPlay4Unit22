package com.shilapi.xcertplay

import android.media.session.MediaSession
import android.os.Looper
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Changan S202 keeps media keys active without owning audio focus so volume stays vehicle-owned. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayMediaFocusDisabledTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<CarPlayController>()

    @Before fun disableMediaKeyFocus() {
        AirPlayPersistence.saveMediaKeyAudioFocus(app, false)
    }

    @After fun cleanup() {
        controllers.forEach(CarPlayMediaKeys::detach)
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveMediaKeyAudioFocus(app, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun mediaAudioCreatesActiveSessionWithoutFocusRequest() {
        val session = start(mock(CarPlayController::class.java).also(controllers::add))

        assertTrue(session.isActive)
        assertFocusDisabled()
    }

    @Test fun repeatedMediaActivityDoesNotRequestFocusOrReplaceSession() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val session = start(controller)

        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(session, currentSession())
        assertFocusDisabled()
    }

    @Test fun replacementControllerRetiresSessionWithoutClaimingFocus() {
        val first = mock(CarPlayController::class.java).also(controllers::add)
        val firstSession = start(first)
        val second = mock(CarPlayController::class.java).also(controllers::add)

        val secondSession = start(second)

        assertFalse(firstSession.isActive)
        assertNotSame(firstSession, secondSession)
        assertTrue(secondSession.isActive)
        assertFocusDisabled()
    }

    @Test fun detachReleasesSessionAndLeavesFocusUnowned() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val session = start(controller)

        CarPlayMediaKeys.detach(controller)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(session.isActive)
        assertNull(currentSession())
        assertFocusDisabled()
    }

    private fun start(controller: CarPlayController): MediaSession {
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFocusDisabled()
        return currentSession().also(::assertNotNull)!!
    }

    private fun currentSession(): MediaSession? =
        ReflectionHelpers.getField(CarPlayMediaKeys, "session")

    private fun assertFocusDisabled() {
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusRequest"))
        assertFalse(ReflectionHelpers.getField(CarPlayMediaKeys, "focusHeld"))
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusOwner"))
    }
}
