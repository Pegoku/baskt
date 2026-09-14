package nl.baskt.ui.search

import nl.baskt.data.Product
import nl.baskt.data.StockItem
import nl.baskt.ui.AppViewModel.Scan
import org.junit.Assert.*
import org.junit.Test

class ScanStockTest {
    private fun product(id: String) = Product(id, "store", id, "Milk", quantityText = "1 l", priceCents = 100)

    @Test fun recognisesAnyStoreMatchAndEquivalentUpcBarcode() {
        val scan = Scan("0036000291452", products = listOf(product("first"), product("other-store")), loading = false)
        val otherStore = StockItem("stock", "Milk", productId = "other-store")
        assertEquals(otherStore, scan.stockItem(listOf(otherStore)))
        val byBarcode = StockItem("stock", "Milk", barcode = "036000291452")
        assertEquals(byBarcode, scan.stockItem(listOf(byBarcode)))
        assertNull(scan.stockItem(listOf(StockItem("unrelated", "Bread", barcode = "4006381333931"))))
    }

    @Test fun mixedBatchPartitionsIntoAddAndRemoveAndTracksStockChanges() {
        val milk = Scan("0036000291452", products = listOf(product("milk")), loading = false)
        val other = Scan("4006381333931", products = listOf(product("other")), loading = false)
        val ready = listOf(milk, other)
        val stock = listOf(StockItem("stock", "Milk", productId = "milk"))
        assertEquals(listOf(other), ready.filter { it.stockItem(stock) == null })
        assertEquals(listOf(milk), ready.filter { it.stockItem(stock) != null })
        assertNull(milk.stockItem(emptyList())) // Removal immediately makes it eligible to add again.
    }
}
