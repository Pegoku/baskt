package nl.baskt.data

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TranslationRepositoryTest {
    private fun disk() = OfflineStore(Files.createTempDirectory("baskt-translation").toFile())

    @Test fun `retries failures and reuses fields after restart across presentations`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val disk = disk()
            var calls = 0
            val repo = TranslationRepository(scope, disk, { texts, language, _ ->
                calls++
                if (calls == 1) TranslationResponse(texts, language, false)
                else TranslationResponse(texts.map { "translated $it" }, language, true)
            }, retryDelay = {})
            assertTrue(repo.translate(listOf("melk", "1 liter", ""), "es").translated)
            assertEquals(2, calls)
            val restored = TranslationRepository(scope, disk, { texts, language, descriptions ->
                assertEquals(listOf("description"), texts)
                assertEquals(listOf(0), descriptions)
                TranslationResponse(listOf("descripción"), language, true)
            })
            val detail = restored.translate(listOf("melk", "description", "1 liter"), "es", listOf(1))
            assertEquals(listOf("translated melk", "descripción", "translated 1 liter"), detail.texts)
            assertEquals(listOf("translated melk"), restored.translate(listOf("melk"), "es").texts)
        } finally { scope.cancel() }
    }

    @Test fun `leaving a row does not cancel the shared request or prevent caching`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val repo = TranslationRepository(scope, disk(), { _, language, _ ->
                calls++
                started.complete(Unit)
                release.await()
                TranslationResponse(listOf("leche"), language, true)
            })
            val first = launch { repo.translate(listOf("melk"), "es") }
            started.await()
            first.cancelAndJoin()
            val second = async { repo.translate(listOf("melk"), "es") }
            release.complete(Unit)
            assertEquals("leche", second.await().texts.single())
            assertEquals("leche", repo.translate(listOf("melk"), "es").texts.single())
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test fun `invalid responses are not cached and languages have separate entries`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var valid = false
            var calls = 0
            val repo = TranslationRepository(scope, disk(), { _, language, _ ->
                calls++
                TranslationResponse(listOf(if (valid) language else ""), language, true)
            }, retryDelay = {})
            assertFalse(repo.translate(listOf("melk"), "es").translated)
            assertEquals(3, calls)
            valid = true
            assertEquals("es", repo.translate(listOf("melk"), "es").texts.single())
            assertEquals("en", repo.translate(listOf("melk"), "en").texts.single())
            assertEquals(5, calls)
        } finally { scope.cancel() }
    }
}
