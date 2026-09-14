package nl.baskt.ui.search

import nl.baskt.ui.common.descriptionBlocks
import org.junit.Assert.*
import org.junit.Test

class DescriptionBlocksTest {
    @Test fun joinsWordPerLineAndLimitsBlankSpacing() {
        val blocks = descriptionBlocks("Made\nwith\nfresh\nmilk.\n\n\n\nKeep\n chilled.")
        assertEquals(listOf("Made with fresh milk.", "Keep chilled."), blocks.map { it.text })
    }
    @Test fun preservesBulletItemsAndWrapsTheirContinuation() {
        val blocks = descriptionBlocks("• No added\nsugar\n• Contains milk\n\nStorage:\nKeep below 7°C.")
        assertEquals(listOf("No added sugar", "Contains milk", "Storage:", "Keep below 7°C."), blocks.map { it.text })
        assertEquals(listOf("•", "•", null, null), blocks.map { it.marker })
        assertTrue(blocks[2].heading)
    }
    @Test fun preservesNumberedInstructionsAndQuantities() {
        val blocks = descriptionBlocks("1. Heat to 180°C.\n2. Bake for\n20 minutes.\n\n250 g flour\n100 ml milk")
        assertEquals(listOf("1.", "2.", null, null), blocks.map { it.marker })
        assertEquals("Bake for 20 minutes.", blocks[1].text)
        assertEquals("250 g flour", blocks[2].text)
        assertEquals("100 ml milk", blocks[3].text)
    }
}
