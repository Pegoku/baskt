package nl.baskt.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Split at word boundaries without losing the tail of long replies. */
fun speechChunks(text: String, limit: Int = 1800): List<String> {
    var remaining = text.replace(Regex("<[^>]*>"), "").replace(Regex("[*`#]"), "").trim()
    val chunks = mutableListOf<String>()
    while (remaining.isNotEmpty()) {
        val end = if (remaining.length <= limit) remaining.length else
            remaining.lastIndexOf(' ', limit).takeIf { it > 0 } ?: limit
        chunks += remaining.substring(0, end)
        remaining = remaining.substring(end).trimStart()
    }
    return chunks
}

class SpeechPlayback(private val context: Context) {
    suspend fun play(bytes: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("speech-", ".mp3", context.cacheDir).also { it.writeBytes(bytes) }
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                val player = MediaPlayer()
                val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                var lostFocus: (() -> Unit)? = null
                val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                        if (change < 0) lostFocus?.invoke()
                    }.build()
                try {
                    player.setAudioAttributes(attributes)
                    player.setDataSource(file.absolutePath)
                    suspendCancellableCoroutine<Unit> { continuation ->
                        lostFocus = { if (continuation.isActive) continuation.cancel() }
                        player.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
                        player.setOnErrorListener { _, _, _ ->
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Audio playback failed"))
                            true
                        }
                        player.setOnPreparedListener {
                            if (continuation.isActive) {
                                if (manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) it.start()
                                else continuation.cancel()
                            }
                        }
                        player.prepareAsync()
                    }
                } finally {
                    lostFocus = null
                    player.release()
                    manager.abandonAudioFocusRequest(focus)
                }
            }
        } finally { file.delete() }
    }
}
