package nl.baskt.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records a dictation to a small AAC/m4a file — the format Whisper accepts and the smallest thing worth
 * uploading over mobile data (16 kHz mono is all speech recognition uses).
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    val recording: Boolean get() = recorder != null

    /** Starts recording; returns false when the microphone is unavailable (busy, or permission revoked). */
    fun start(): Boolean {
        stopRecorder()
        val target = File(context.cacheDir, "dictation.m4a")
        val instance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return runCatching {
            instance.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            instance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            instance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            instance.setAudioSamplingRate(16_000)
            instance.setAudioChannels(1)
            instance.setAudioEncodingBitRate(32_000)
            instance.setOutputFile(target.absolutePath)
            instance.prepare()
            instance.start()
            recorder = instance
            file = target
            true
        }.getOrElse {
            runCatching { instance.release() }
            false
        }
    }

    /** Loudness of the last sampling window (0 when not recording); drives the meter and silence detection. */
    fun amplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    /** Stops and returns the recording, or null when nothing usable was captured. */
    fun stop(): ByteArray? {
        val stopped = stopRecorder()
        val bytes = file?.takeIf { stopped && it.length() > 1024 }?.readBytes()
        file?.delete()
        file = null
        return bytes
    }

    fun cancel() {
        stopRecorder()
        file?.delete()
        file = null
    }

    private fun stopRecorder(): Boolean {
        val instance = recorder ?: return false
        recorder = null
        // stop() throws when the clip is too short to have produced a valid file; the caller treats that as "nothing said".
        val stopped = runCatching { instance.stop() }.isSuccess
        runCatching { instance.release() }
        return stopped
    }
}
