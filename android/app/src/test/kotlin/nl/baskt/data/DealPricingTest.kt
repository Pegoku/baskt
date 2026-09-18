package nl.baskt.data

import org.junit.Assert.*
import org.junit.Test

class DealPricingTest {
    private fun product(price: Int, deal: String?, regular: Int? = null, amount: Double? = 0.2, unit: String? = "kg") =
        Product("AH:1", "AH", "1", "Wasabi bollen", quantityText = "200 g", unitAmount = amount, unit = unit, priceCents = price, regularPriceCents = regular, dealText = deal)

    @Test fun nForPriceGivesEachAndUnitPrice() {
        val deal = product(249, "3 voor 7.00").dealPricing()!!
        assertEquals(3, deal.count)
        assertEquals(700, deal.bundleCents)
        assertEquals(233, deal.eachCents)
        assertEquals(1167, deal.unitPriceCents)
        assertEquals("€2,33 each · €11,67/kg", deal.label())
    }

    @Test fun jumboCommaPricesAndTrailingPeriodText() {
        val deal = product(369, "2 voor 5,00 • wo 16 t/m di 22 sep", amount = 0.25).dealPricing()!!
        assertEquals(250, deal.eachCents)
        assertEquals(1000, deal.unitPriceCents)
    }

    @Test fun buyNGetMFreeUsesListedPrice() {
        assertEquals(125, product(250, "1 + 1 gratis", amount = null, unit = null).dealPricing()!!.eachCents)
        assertEquals(200, product(300, "2+1 gratis").dealPricing()!!.eachCents)
        assertNull(product(250, "1 + 1 gratis", amount = null).dealPricing()!!.unitLabel())
    }

    @Test fun secondHalfPrice() {
        val deal = product(355, "2e Halve Prijs", amount = 0.35).dealPricing()!!
        assertEquals(2, deal.count)
        assertEquals(533, deal.bundleCents)
        assertEquals(267, deal.eachCents)
    }

    @Test fun pieceUnitsAndAlreadyDiscountedDealsAreSkipped() {
        assertEquals("€0,25 each · €0,06/st", product(50, "2 voor 0,50", amount = 4.0, unit = "piece").dealPricing()!!.label())
        assertNull(product(150, "25% korting", regular = 200).dealPricing())
        assertNull(product(249, "VOOR 2.49", regular = 369).dealPricing())
        assertNull(product(200, "2 voor 4,00", regular = 300).dealPricing())
        assertNull(product(599, "Gratis bezorging • Vanaf € 12,00").dealPricing())
        assertNull(product(249, null).dealPricing())
    }
}
