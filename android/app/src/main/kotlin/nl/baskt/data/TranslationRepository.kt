package nl.baskt.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Requests outlive a scrolling row; successful fields are reusable in every presentation. */
class TranslationRepository(
    private val scope: CoroutineScope,
    private val offline: OfflineStore,
    private val request: suspend (List<String>, String, List<Int>) -> TranslationResponse,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val slots = Semaphore(2)
    private val pending = mutableMapOf<String, Deferred<TranslationResponse>>()

    suspend fun translate(texts: List<String>, language: String, descriptions: List<Int> = emptyList()): TranslationResponse {
        val key = "translation-v2-$language-${descriptions.joinToString(",")}-${texts.joinToString("\u0000")}"
        val work = synchronized(pending) {
            pending[key] ?: scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                resolve(texts, language, descriptions)
            }.also { task ->
                pending[key] = task
                task.invokeOnCompletion { synchronized(pending) { if (pending[key] === task) pending.remove(key) } }
            }
        }
        return work.await()
    }

    // v2 drops fields cached when the server could still hand a product name back untranslated.
    // Fields are the only cache, so a title translated in the product sheet also shows in its row.
    private suspend fun resolve(texts: List<String>, language: String, descriptions: List<Int>): TranslationResponse {
        fun fieldKey(index: Int) = "translation-field-v2-$language-${index in descriptions}-${texts[index]}"
        fun valid(result: TranslationResponse, source: List<String>) = result.translated && result.language == language &&
            result.texts.size == source.size && result.texts.zip(source).all { (translated, original) -> original.isBlank() || translated.isNotBlank() }
        // Disk hits must not queue behind slow AI requests for unrelated products.
        val cached = texts.mapIndexed { index, text -> if (text.isBlank()) text else offline.load<String>(fieldKey(index)) }
        if (cached.all { it != null }) return TranslationResponse(cached.map { it!! }, language, true)
        repeat(3) { attempt ->
            val result = slots.withPermit {
                val shown = texts.mapIndexed { index, text -> if (text.isBlank()) text else offline.load<String>(fieldKey(index)) }.toMutableList()
                val missing = texts.indices.filter { shown[it] == null }
                if (missing.isEmpty()) return@withPermit TranslationResponse(shown.map { it!! }, language, true)
                val source = missing.map { texts[it] }
                val response = try {
                    request(source, language, missing.mapIndexedNotNull { index, original -> index.takeIf { original in descriptions } })
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                if (response != null && valid(response, source)) {
                    missing.forEachIndexed { index, original ->
                        shown[original] = response.texts[index]
                        synchronized(offline) { offline.save(fieldKey(original), response.texts[index]) }
                    }
                    TranslationResponse(shown.map { it!! }, language, true)
                } else null
            }
            if (result != null) return result
            if (attempt < 2) retryDelay(2_000L * (attempt + 1))
        }
        return TranslationResponse(texts, language, false)
    }
}
