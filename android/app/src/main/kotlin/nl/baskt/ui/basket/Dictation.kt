package nl.baskt.ui.basket

/**
 * Where a microphone tap goes. The server's Whisper hears mixed-language grocery talk far better than the
 * phone does, but it needs a connection and a configured provider; otherwise Android's recogniser answers.
 */
fun useServerDictation(online: Boolean, serverStt: Boolean) = online && serverStt

/**
 * Ends a recording once the speaker goes quiet, the way the phone's own recogniser does, so nobody has to
 * reach for a stop button mid-sentence. The noise floor follows how loud the speaker actually was, so a
 * busy kitchen does not keep the microphone open and a whisper still ends the turn.
 */
class SilenceDetector(private val silenceMs: Long = 1_800, private val speechAmplitude: Int = 2_000) {
    private var peak = 0
    private var heardSpeech = false
    private var quietSince: Long? = null

    /** Feeds one sample (`amplitude` 0..32767) taken `elapsedMs` into the recording; true means stop now. */
    fun update(amplitude: Int, elapsedMs: Long): Boolean {
        peak = maxOf(peak, amplitude)
        if (amplitude >= speechAmplitude) heardSpeech = true
        if (!heardSpeech) return false // never cut off someone who has not started talking yet
        if (amplitude > maxOf(1_500, peak / 6)) {
            quietSince = null
            return false
        }
        val since = quietSince ?: elapsedMs.also { quietSince = it }
        return elapsedMs - since >= silenceMs
    }
}
