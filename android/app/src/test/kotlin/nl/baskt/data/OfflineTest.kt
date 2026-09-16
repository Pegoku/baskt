package nl.baskt.data

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflineTest {
    private fun store() = OfflineStore(Files.createTempDirectory("baskt-offline-test").toFile())
    private fun api(port: Int = 1) = BasktApi(settingsProvider = { AppSettings("http://127.0.0.1:$port", "test") })

    @Test fun `offline edits persist across restart without attempting network`() = runBlocking {
        val disk = store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = BasketRepository(api(), scope, disk)
            repo.online.value = false
            repo.restoreBasket("default")
            repo.add("Milk", 1)
            val item = repo.items.value.single()
            repo.setQuantity(item, 3)
            repo.setChecked(item, true)
            repo.addStock("Eggs")
            repo.removeStock(repo.stock.value.single())
            val restored = BasketRepository(api(), scope, disk)
            restored.restoreBasket("default")
            assertEquals(3, restored.items.value.single().quantity)
            assertTrue(restored.items.value.single().checked)
            assertTrue(restored.stock.value.isEmpty())
            assertEquals(listOf("add", "quantity", "checked", "stockAdd", "stockRemove"), restored.pending.value.map { it.type })
        } finally { scope.cancel() }
    }

    @Test fun `recipe creation editing favouriting and deletion work offline`() = runBlocking {
        val disk = store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = BasketRepository(api(), scope, disk).also { it.online.value = false }
            val recipes = RecipeLibrary(api(), disk, repo)
            val original = recipes.save(RecipeDraft("Soup", ingredientLines = listOf("2 carrots"), steps = listOf(RecipeStep("Boil"))), "manual")
            recipes.save(RecipeDraft("Carrot soup", ingredientLines = listOf("3 carrots")), "manual", original.id)
            recipes.toggle(RecipeSummary("Soup", original.url))
            recipes.recordViewed(RecipeSummary("Pasta", "https://example.com/pasta"))
            recipes.recordViewed(RecipeSummary("Soup", original.url))
            recipes.recordViewed(RecipeSummary("Pasta", "https://example.com/pasta"))
            val restored = RecipeLibrary(api(), disk, repo).also { it.reloadLocal() }
            assertEquals("Carrot soup", restored.detail(original.url)?.title)
            assertEquals(1, restored.favourites.value.size)
            assertEquals(listOf("https://example.com/pasta", original.url), restored.recent.value.map { it.url })
            restored.delete(original.id)
            assertTrue(restored.mine.value.isEmpty())
            assertEquals(listOf("https://example.com/pasta"), restored.recent.value.map { it.url })
            assertEquals(listOf("recipeSave", "recipeUpdate", "favouriteAdd", "recipeDelete"), repo.pending.value.map { it.type })
        } finally { scope.cancel() }
    }

    @Test fun `failed replay retains operation and restart resolves created ids`() = runBlocking {
        val disk = store()
        val requests = mutableListOf<String>()
        var reject = true
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            val path = request.requestURI.path
            requests.add("${request.requestMethod} $path")
            val status = if (path.endsWith("real-item") && reject) 503 else 200
            val body = if (status == 503) """{"error":{"message":"try later"}}""" else """{"id":"real-item","text":"Milk","status":"MATCHED"}"""
            request.responseHeaders.add("Content-Type", "application/json")
            request.sendResponseHeaders(status, body.toByteArray().size.toLong())
            request.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val client = api(server.address.port)
            val repo = BasketRepository(client, scope, disk).also { it.online.value = false }
            repo.add("Milk", 1)
            repo.setQuantity(repo.items.value.single(), 2)
            repo.replayQueue()
            assertEquals(1, repo.pending.value.size)
            assertNotNull(repo.pending.value.single().failure)
            reject = false
            val restarted = BasketRepository(client, scope, disk)
            restarted.replayQueue()
            assertTrue(restarted.pending.value.isEmpty())
            assertEquals(1, requests.count { it == "POST /api/v1/basket/items" })
            assertEquals(2, requests.count { it == "PATCH /api/v1/basket/items/real-item" })
        } finally { scope.cancel(); server.stop(0) }
    }

    @Test fun `a late server response cannot overwrite a newer offline edit`() = runBlocking {
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            arrived.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val body = """{"items":[]}"""
            request.responseHeaders.add("Content-Type", "application/json")
            request.sendResponseHeaders(200, body.toByteArray().size.toLong())
            request.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = BasketRepository(api(server.address.port), scope, store())
            val refresh = async { repo.refreshStock() }
            withContext(Dispatchers.IO) { assertTrue(arrived.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            repo.online.value = false
            repo.addStock("Local eggs")
            release.countDown()
            refresh.await()
            assertEquals("Local eggs", repo.stock.value.single().text)
            assertEquals(1, repo.pending.value.size)
        } finally { release.countDown(); scope.cancel(); server.stop(0) }
    }

    @Test fun `cache keys and credentials never collide`() {
        val dir = Files.createTempDirectory("baskt-namespace-test").toFile()
        var account = "one"
        val disk = OfflineStore(dir) { account }
        disk.save("https://a/b?x=1", "first")
        disk.save("https://a/b_x=1", "second")
        assertEquals("first", disk.load<String>("https://a/b?x=1"))
        account = "two"
        assertNull(disk.load<String>("https://a/b?x=1"))
        account = "one"
        assertEquals("first", disk.load<String>("https://a/b?x=1"))
    }

    @Test fun `local recipe scaling and copied folder children survive restart`() = runBlocking {
        val disk = store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = BasketRepository(api(), scope, disk).also { it.online.value = false }
            repo.addGroup("Soup", listOf("2 carrots"), RecipeInfo("Soup", servings = "2", ingredientLines = listOf("2 carrots"), currentServings = 2.0))
            val group = repo.items.value.first { it.isGroup }
            repo.setGroupServings(group, 4)
            assertEquals("4 carrots", repo.items.value.first { !it.isGroup }.text)
            val target = repo.createBasket("Trip", null)
            repo.transfer(repo.items.value.first { it.isGroup }, target.id, true)
            repo.switchBasket(target.id)
            assertEquals(2, repo.items.value.size)
            assertEquals(repo.items.value.first { it.isGroup }.id, repo.items.value.first { !it.isGroup }.parentId)
            val restored = BasketRepository(api(), scope, disk)
            restored.restoreBasket(target.id)
            assertEquals("4 carrots", restored.items.value.first { !it.isGroup }.text)
            assertEquals("0.75 kg flour", scaleIngredient("½ kg flour", 1.5))
            assertEquals("2-4 eggs", scaleIngredient("1-2 eggs", 2.0))
        } finally { scope.cancel() }
    }

    @Test fun `thumbs down removes downloaded product and thumbs up persists selection`() = runBlocking {
        val disk = store()
        val p = Product("milk", "AH", "milk", "Milk", quantityText = "1 l", priceCents = 100)
        val q = p.copy(id = "other", title = "Other milk")
        val item = BasketItem("item", text = "milk", status = "MATCHED", matches = listOf(StoreMatch("AH", "PENDING", provisional = p, options = listOf(p, q))))
        disk.save("items-default", listOf(item))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = BasketRepository(api(), scope, disk).also { it.online.value = false; it.restoreBasket("default") }
            repo.feedback(item, "AH", p.id, false)
            assertEquals(q.id, repo.items.value.single().match("AH")?.effective?.id)
            repo.feedback(repo.items.value.single(), "AH", q.id, true)
            val restored = BasketRepository(api(), scope, disk).also { it.restoreBasket("default") }
            assertEquals(q.id, restored.items.value.single().match("AH")?.chosen?.id)
            assertEquals(listOf(false, true), restored.pending.value.map { it.checked })
        } finally { scope.cancel() }
    }

    @Test fun `discarding an offline folder removes dependent child edits`() = runBlocking {
        val disk = store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = BasketRepository(api(), scope, disk).also { it.online.value = false }
            repo.addGroup("Dinner", listOf("Eggs"))
            repo.setChecked(repo.items.value.first { !it.isGroup }, true)
            repo.discard(repo.pending.value.first())
            assertTrue(repo.pending.value.isEmpty())
            assertTrue(repo.items.value.isEmpty())
            assertTrue(disk.loadOps().isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun `comparison uses offline quantities picks assignments and checks`() {
        val product = Product("milk", "AH", "milk", "Milk", quantityText = "1 l", unitAmount = 1.0, unit = "l", priceCents = 100)
        val item = BasketItem("item", text = "milk", quantity = 2, status = "MATCHED", assignedStore = "AH", matches = listOf(StoreMatch("AH", "CHOSEN", chosen = product)))
        val stores = listOf(StoreInfo("AH", "AH", "#fff"))
        assertEquals(200, localComparison(listOf(item), stores, "price").mixAndMatchTotalCents)
        assertEquals(200, localComparison(listOf(item), stores, "price").order.perStore["AH"]?.totalCents)
        assertEquals(0, localComparison(listOf(item.copy(checked = true)), stores, "price").mixAndMatchTotalCents)
    }
}
