package nl.baskt.data

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflineBarcodeTest {
    private fun store() = OfflineStore(Files.createTempDirectory("baskt-barcode-test").toFile())
    private fun barcodes(disk: OfflineStore) = OfflineBarcodes(disk)

    @Test fun `open food facts answer becomes a label with the brand in front`() {
        val label = barcodes(store()).parse(
            """{"status":1,"product":{"product_name":"Hazelnootpasta","brands":"Nutella, Ferrero","quantity":"400 g","image_front_small_url":"https://images.example/front.jpg"}}""",
        )
        assertEquals("Nutella Hazelnootpasta", label?.title)
        assertEquals("400 g", label?.quantityText)
        assertEquals("https://images.example/front.jpg", label?.imageUrl)
        assertEquals("openfoodfacts", label?.source)
    }

    @Test fun `a brand already in the name is not repeated`() {
        val label = barcodes(store()).parse("""{"product":{"product_name":"Nutella 400g","brands":"Nutella"}}""")
        assertEquals("Nutella 400g", label?.title)
    }

    @Test fun `nameless or broken answers are not labels`() {
        val phone = barcodes(store())
        assertNull(phone.parse("""{"status":0}"""))
        assertNull(phone.parse("""{"product":{"brands":"Ferrero"}}"""))
        assertNull(phone.parse("not json at all"))
    }

    @Test fun `a previous server lookup names the code without any connection`() {
        val disk = store()
        val product = Product(id = "AH:1", store = "AH", sourceId = "1", title = "AH Bananen tros", quantityText = "5 stuks", priceCents = 119, imageUrl = "https://images.example/ah.jpg")
        disk.save("barcode-8718265184886", BarcodeResponse("8718265184886", listOf(BarcodeResult("AH", product))))
        val label = barcodes(disk).known("8718265184886")
        assertEquals("AH Bananen tros", label?.title)
        assertEquals("cache", label?.source)
    }

    @Test fun `something in stock names the code, whatever zero padding the scanner returns`() {
        val stock = listOf(StockItem(id = "s1", text = "Krulslamelange", quantityText = "175 g", barcode = "8718906535527"))
        val label = barcodes(store()).known("08718906535527", stock)
        assertEquals("Krulslamelange", label?.title)
        assertEquals("stock", label?.source)
    }

    @Test fun `a scan in the shop carries the phone's name for the item straight away`() = runBlocking {
        val disk = store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val product = Product(id = "AH:2", store = "AH", sourceId = "2", title = "AH Bananen tros", quantityText = "5 stuks", priceCents = 119)
            disk.save("barcode-8718265184886", BarcodeResponse("8718265184886", listOf(BarcodeResult("AH", product))))
            val repo = BasketRepository(BasktApi(settingsProvider = { AppSettings("http://127.0.0.1:1", "test") }), scope, disk)
            repo.online.value = false
            repo.scanItem("AH", "8718265184886")
            val scan = repo.tripScans.value.single()
            assertEquals("AH Bananen tros", scan.label?.title)
            assertFalse(scan.synced)
        } finally {
            scope.cancel()
        }
    }
}
