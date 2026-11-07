package nl.baskt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bodies from the in-store capture (docs/ah-selfscan.md), card number replaced by an equally shaped one. */
class SelfScanTest {
    private val session = """{"result":{"token":"cb3e799887ffa392b074b38e9b1c04d8","eanRis":[{"maxRange":"2620572002999","minRange":"2620572000000","eanType":"BOK"},{"maxRange":"2623069039996","eanType":"BOK","minRange":"2623064040003"}]}}"""

    private val basket = """{"result":{"basket":{"transactionId":"AH21b087","discounts":[{"text":"","descriptions":["ALLEAHPIZZAD","2 PIZZADEEG   "],"items":["8719587149300"],"icon":"bonus_mini","discount":249}],"totals":{"personalDiscount":0,"regularItemCount":3,"itemCount":3,"uniqueItemCount":2,"subtotal":498,"bonusDiscount":249,"discount":249},"items":[{"description":"Bonuskaart","flags":["BOK"],"plusAllowed":false,"barcode":"2623065933434","private":"65933434"},{"description":"PIZZADEEG","price":498,"flags":["ART","OEDI","BONUS","OEPrefix"],"bonusInfo":{"promoLabel":"BONUS"},"barcode":"8719587149300","nasaNr":"46445","quantity":2,"icons":["bonus"]},{"barcode":"8710400200857","quantity":1,"flags":["ART","PRICEFAV"],"price":199,"nasaNr":"21006","description":"PAPRIKA MIX","unknownField":{"x":1}}]}}}"""

    private val paymentTotals = """{"message":{"code":"GOTO_PAYTOWER","level":"message","message":"Ga naar een betaalpaal en scan onderstaande streepjescode"},"result":{"totals":{"discount":249,"bonusDiscount":249,"personalDiscount":0,"itemCount":9,"total":1352},"barcode":"2623065933434","symbology":"EAN13","imageUrl":"https://selfscan.store.ah.nl:8881/api/selfscan/v2.0/assets/barc/P2623065933434.gif","backButton":{"enabled":true},"showToPay":true}}"""

    private val paid = """{"result":{"imageUrl":"x","symbology":"EAN13","backButton":{"label":"Klaar"},"status":"paid","barcode":"2623065933434"},"message":{"code":"TRANSACTION_PAID_CODE","level":"info","message":"Je hebt betaald. Scan onderstaande code om het hekje te openen."}}"""

    // Field capture 2026-11-07: Appie scanning a chicken fillet's GS1 DataMatrix (GTIN + best-before, no weight).
    private val chicken = """{"result":{"basket":{"items":[{"plusAllowed":false,"flags":["BOK"],"private":"65933434","barcode":"2623065933434","description":"Bonuskaart"},{"nasaNr":"805097","plusAllowed":false,"description":"KIPFILET","barcode":"010871890768042417261115","price":955,"flags":["ART","THT","PRICEFAV"],"quantity":1}],"totals":{"personalDiscount":0,"bonusDiscount":0,"subtotal":955,"itemCount":1,"discount":0,"uniqueItemCount":1,"regularItemCount":1},"discounts":[],"transactionId":"AH21b080"}}}"""

    // The same scan two minutes earlier: HTTP 200, basket untouched, the card held until the service desk freed it.
    private val bonusBlocked = """{"result":{"basket":{"items":[{"private":"65933434","description":"Bonuskaart","barcode":"2623065933434","flags":["BOK"],"plusAllowed":false}],"totals":{"subtotal":0,"discount":0},"discounts":[]}},"message":{"level":"error","message":"Vraag om assistentie bij het servicepunt.","code":"BONUS_BLOCKED"}}"""

    @Test fun sessionGivesTokenAndCardRanges() {
        val result = decodeSelfScan(session, SsSession.serializer()).result
        assertEquals("cb3e799887ffa392b074b38e9b1c04d8", result?.token)
        assertEquals(2, result?.eanRis?.size)
    }

    @Test fun theMembersCardFallsInABonuskaartRange() {
        val ranges = decodeSelfScan(session, SsSession.serializer()).result!!.eanRis
        assertTrue(isBonuskaart("2623065933434", ranges))
        assertFalse(isBonuskaart("8719587149300", ranges))
        assertFalse(isBonuskaart("262306593343", ranges)) // a digit short
        assertFalse(isBonuskaart("2623069039997", ranges)) // one past the range
        assertFalse(isBonuskaart("abc", ranges))
    }

    @Test fun basketHidesTheCardLineAndTotalsAreNet() {
        val parsed = decodeSelfScan(basket, SsBasketResult.serializer()).result?.basket
        assertNotNull(parsed)
        assertEquals(3, parsed!!.items.size)
        assertEquals(listOf("PIZZADEEG", "PAPRIKA MIX"), parsed.products.map { it.description })
        assertEquals(498, parsed.toPayCents)
        assertEquals(249, parsed.savedCents)
        assertEquals(3, parsed.itemCount)
        assertEquals("65933434", parsed.items.first().privateNumber)
        assertEquals("ALLEAHPIZZAD · 2 PIZZADEEG", parsed.discounts.single().label)
    }

    @Test fun lineTagsFollowTheFlags() {
        val parsed = decodeSelfScan(basket, SsBasketResult.serializer()).result!!.basket!!
        assertEquals(listOf("BONUS"), parsed.item("8719587149300")!!.tags())
        assertEquals(listOf("Prijsfavoriet"), parsed.item("8710400200857")!!.tags())
        assertTrue(SsItem(barcode = "1", flags = listOf("ART", "AKTIE")).inPromotion)
        assertFalse(SsItem(barcode = "1", flags = listOf("ART", "AKTIE", "BONUS")).inPromotion)
    }

    @Test fun weighedLineShowsItsWeight() {
        assertEquals("0,512 kg", SsItem(barcode = "2", unitInfo = SsUnitInfo(price = 1299, quantity = 0.512, type = "kg")).weightText())
        assertNull(SsItem(barcode = "2").weightText())
    }

    @Test fun checkoutCarriesTheAmountAndTheCard() {
        val envelope = decodeSelfScan(paymentTotals, SsPaymentTotals.serializer())
        assertEquals("GOTO_PAYTOWER", envelope.message?.code)
        assertFalse(envelope.message!!.isProblem)
        assertEquals(1352, envelope.result?.totals?.total)
        assertEquals("2623065933434", envelope.result?.barcode)
        val status = decodeSelfScan(paid, SsPaymentStatus.serializer())
        assertEquals("paid", status.result?.status)
        assertTrue(SelfScanCheckout(status = status.result?.status).paid)
    }

    @Test fun refusalsAndJunkBodies() {
        val refused = decodeSelfScan("""{"message":{"code":"BARCODE_UNKNOWN","level":"alert","message":"Barcode onbekend"}}""", SsBasketResult.serializer())
        assertTrue(refused.message!!.isProblem)
        assertNull(refused.result)
        assertNull(decodeSelfScan("<html>502</html>", SsBasketResult.serializer()).result)
        assertNull(decodeSelfScan("", SsService.serializer()).message)
    }

    @Test fun aDatedDataMatrixGoesUpAsItsElementString() {
        // What Appie sent for the chicken: the element string as is, no symbology, no FNC1.
        assertEquals("010871890768042417261115", selfScanCodes("]d2010871890768042417261115").first())
        assertEquals("pack", tillCodeKind("]d2010871890768042417261115"))
        val line = decodeSelfScan(chicken, SsBasketResult.serializer()).result!!.basket!!.item("010871890768042417261115")!!
        assertEquals("KIPFILET", line.description)
        assertEquals(955, line.price)
        assertTrue(line.hasDate)
        assertNull(line.weightText())
        assertEquals(listOf("Prijsfavoriet"), line.tags())
        assertEquals(listOf("Prijsfavoriet", "− €0,50 short date"), line.copy(wasteDiscount = 50).tags())
    }

    @Test fun aBlockedCardIsAProblemWithItsOwnText() {
        val envelope = decodeSelfScan(bonusBlocked, SsBasketResult.serializer())
        assertTrue(envelope.message!!.isProblem)
        assertTrue(envelope.result!!.basket!!.products.isEmpty())
        assertTrue(envelope.message!!.shopperText()!!.contains("service desk"))
        assertNull(SsMessage(code = "SOMETHING_NEW", message = "x").shopperText())
    }

    @Test fun selfScanDropsTheSymbologyAndFallsBackToTheGtin() {
        // The lettuce from the store: GS1-128 with a GTIN and a best-before date.
        assertEquals(listOf("010871890762987417261114", "8718907629874"), selfScanCodes("]C1010871890762987417261114"))
        assertEquals(listOf("010871958748100417261115", "8719587481004"), selfScanCodes("]d2\u001d010871958748100417261115"))
        assertEquals(listOf("8719587149300"), selfScanCodes("8719587149300"))
        // A weighed label keeps only the full string: its GTIN alone would not say how much.
        assertEquals(listOf("0128700000000003310300512"), selfScanCodes("]C10128700000000003310300512"))
        assertEquals(emptyList<String>(), selfScanCodes("]C1"))
    }
}
