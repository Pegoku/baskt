package nl.baskt.ui.basket

import org.junit.Assert.*
import org.junit.Test

class DictationTest {
    @Test fun serverDictationNeedsAConnectionAndAProvider() {
        assertTrue(useServerDictation(online = true, serverStt = true))
        assertFalse(useServerDictation(online = false, serverStt = true))
        assertFalse(useServerDictation(online = true, serverStt = false))
    }

    @Test fun silenceAfterSpeechEndsTheRecording() {
        val detector = SilenceDetector()
        assertFalse(detector.update(9_000, 100)) // talking
        assertFalse(detector.update(400, 1_000)) // just gone quiet
        assertFalse(detector.update(400, 2_000))
        assertTrue(detector.update(400, 2_900)) // 1.9 s of quiet
    }

    @Test fun quietBeforeTheFirstWordDoesNotCount() {
        val detector = SilenceDetector()
        for (elapsed in longArrayOf(100, 2_000, 5_000, 9_000)) assertFalse(detector.update(200, elapsed))
        assertFalse(detector.update(8_000, 9_100))
    }

    @Test fun aPauseBetweenItemsRestartsTheCountdown() {
        val detector = SilenceDetector()
        detector.update(9_000, 100)
        assertFalse(detector.update(300, 1_000))
        assertFalse(detector.update(9_000, 2_000)) // speaking again
        assertFalse(detector.update(300, 2_500))
        assertTrue(detector.update(300, 4_400))
    }

    @Test fun aLoudRoomRaisesTheFloorInsteadOfRecordingForever() {
        val detector = SilenceDetector()
        detector.update(24_000, 100) // loud speaker: floor becomes 4000
        assertFalse(detector.update(3_000, 500))
        assertTrue(detector.update(3_000, 2_400))
    }
}
