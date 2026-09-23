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

class DealSavingTest {
    private fun product(id: String, price: Int, deal: String? = null, amount: Double? = 0.2, unit: String? = "kg", unitPrice: Int? = null, unitUnit: String? = null) =
        Product(id, "AH", "1", "Kaas", quantityText = "200 g", unitAmount = amount, unit = unit, priceCents = price, unitPriceCents = unitPrice, unitPriceUnit = unitUnit, dealText = deal, isDeal = deal != null)

    private fun deal(product: Product, current: Product?) = Deal(itemId = "i1", itemText = "kaas", store = "AH", product = product, currentPriceCents = current?.priceCents).saving(current)

    @Test fun multiBuyIsPricedPerPackAndPerKilo() {
        val saving = deal(product("AH:2", 249, "3 voor 7.00"), product("AH:1", 299))
        assertEquals(233, saving.eachCents)
        assertEquals(3, saving.packCount)
        assertEquals(66, saving.packSavingCents)
        assertEquals(1167, saving.unitPriceCents)
        assertEquals(1495, saving.currentUnitPriceCents)
        assertEquals(22, saving.percentOff)
        assertEquals("Save €0,66 a pack when you take 3", saving.savingLine())
        assertEquals("€11,67/kg with the deal, now €14,95/kg", saving.unitLine())
        // The strip leads with the price per kilo, and the pack saving goes underneath.
        assertEquals(DealSaving.PriceStep(1495, 1167, "kg"), saving.step())
        assertEquals("Save €0,66 a pack when you take 3", saving.detail())
        assertEquals("Save €0,66 a pack when you take 3 · €1,32 for 2", saving.detail(quantity = 2))
    }

    @Test fun aBiggerPackCanCostMoreAndStillBeCheaperPerKilo() {
        val saving = deal(product("AH:2", 400, amount = 0.5), product("AH:1", 200, amount = 0.2))
        assertEquals(800, saving.unitPriceCents)
        assertEquals(1000, saving.currentUnitPriceCents)
        assertEquals(20, saving.percentOff)
        // Nothing is saved on the pack, so the share per kilo carries it, and the dearer pack is said plainly.
        assertNull(saving.savingLine())
        assertEquals(DealSaving.PriceStep(1000, 800, "kg"), saving.step())
        assertEquals("€4,00 a pack, now €2,00", saving.detail())
        assertTrue(saving.worthShowing())
    }

    @Test fun theWholeLineIsAddedUpWhenMoreThanOnePackIsWanted() {
        val saving = deal(product("AH:2", 150), product("AH:1", 200))
        assertEquals("Save €0,50 a pack · €1,50 for 3", saving.savingLine(quantity = 3))
    }

    @Test fun unitsThatDoNotMatchAreComparedOnThePackInstead() {
        val saving = deal(product("AH:2", 150, unit = "l", amount = 1.0), product("AH:1", 200, unit = "kg", amount = 0.2))
        assertNull(saving.currentUnitPriceCents)
        assertEquals(25, saving.percentOff)
        assertEquals("€1,50/l with the deal", saving.unitLine())
        assertEquals(DealSaving.PriceStep(200, 150, null), saving.step())
        assertEquals("€1,50/l", saving.detail())
    }

    @Test fun withoutAPickOrAPackSizeOnlyWhatIsKnownIsSaid() {
        val noCurrent = deal(product("AH:2", 249, "3 voor 7.00"), null)
        assertNull(noCurrent.packSavingCents)
        assertNull(noCurrent.percentOff)
        assertNull(noCurrent.savingLine())
        assertEquals("€11,67/kg with the deal", noCurrent.unitLine())
        assertEquals(DealSaving.PriceStep(null, 1167, "kg"), noCurrent.step())
        assertEquals("€2,33 a pack when you take 3", noCurrent.detail())
        val noSizes = deal(product("AH:2", 150, amount = null, unit = null), product("AH:1", 200, amount = null, unit = null))
        assertNull(noSizes.unitLine())
        assertEquals("Save €0,50 a pack", noSizes.savingLine())
        assertEquals(DealSaving.PriceStep(200, 150, null), noSizes.step())
        assertNull(noSizes.detail())
        assertTrue(noSizes.worthShowing())
        // A plain product with nothing known about it has no strip to show.
        val bare = deal(product("AH:2", 150, amount = null, unit = null), null)
        assertFalse(bare.worthShowing())
    }

    @Test fun theListedUnitPriceIsScaledWhenThePackSizeIsUnknown() {
        val saving = deal(product("AH:2", 200, "1 + 1 gratis", amount = null, unit = null, unitPrice = 400, unitUnit = "kg"), product("AH:1", 250, amount = null, unit = null, unitPrice = 500, unitUnit = "kg"))
        assertEquals(100, saving.eachCents)
        assertEquals(200, saving.unitPriceCents)
        assertEquals(500, saving.currentUnitPriceCents)
        assertEquals(60, saving.percentOff)
    }

    @Test fun aMultipackPricedPerPieceIsHeldAgainstTheSingleItemAsOnePiece() {
        // Three cartons for €5,10 against one carton at €0,82: dearer per piece, so no deal for this entry.
        val threePack = Product("AH:2", "AH", "2", "Campina 3-pack", quantityText = "3 stuks", unitAmount = 3.0, unit = "piece", priceCents = 510, dealText = "5% volume voordeel", isDeal = true)
        val carton = Product("AH:1", "AH", "1", "Melk", quantityText = "1 l", unitAmount = 1.0, unit = "l", priceCents = 82)
        val saving = deal(threePack, carton)
        assertEquals(DealSaving.PriceStep(82, 170, "st"), saving.step())
        assertNull(saving.percentOff)
        assertTrue(saving.worse)
        // Eight for €5,00 works out cheaper per piece, so that one stays.
        val eightPack = threePack.copy(id = "AH:3", quantityText = "8 stuks", unitAmount = 8.0, priceCents = 500)
        val better = deal(eightPack, carton)
        assertEquals(DealSaving.PriceStep(82, 63, "st"), better.step())
        assertFalse(better.worse)
    }

    @Test fun aDealAtTheSamePriceOrDearerIsWorse() {
        assertTrue(deal(product("AH:2", 200), product("AH:1", 200)).worse)
        assertTrue(deal(product("AH:2", 250), product("AH:1", 200)).worse)
        assertFalse(deal(product("AH:2", 150), product("AH:1", 200)).worse)
        // Nothing bought yet: nothing to be worse than.
        assertFalse(deal(product("AH:2", 250), null).worse)
    }

    @Test fun aMultiBuyOnThePackSaysWhatHasToBeTaken() {
        val saving = deal(product("AH:2", 249, "3 voor 7.00", amount = null, unit = null), product("AH:1", 299, amount = null, unit = null))
        assertEquals(DealSaving.PriceStep(299, 233, null), saving.step())
        assertEquals("When you take 3", saving.detail())
        assertEquals("When you take 3 · €1,32 for 2", saving.detail(quantity = 2))
    }
}
