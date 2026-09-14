package nl.baskt.ui.common

internal data class DescriptionBlock(val text: String, val marker: String? = null, val heading: Boolean = false)

/** Repair hard-wrapped source/translated text without rewriting its facts or list structure. */
internal fun descriptionBlocks(text: String): List<DescriptionBlock> {
    val blocks = mutableListOf<DescriptionBlock>()
    val lines = mutableListOf<String>()
    var marker: String? = null
    fun flush() {
        if (lines.isNotEmpty()) blocks += DescriptionBlock(lines.joinToString(" "), marker)
        lines.clear()
        marker = null
    }
    val bullet = Regex("^(•|[-*]|\\d+[.)])\\s+(.+)$")
    val quantityLine = Regex("^\\d+(?:[.,]\\d+)?\\s*(?:kg|g|mg|ml|cl|l|kcal|kJ|%)\\b.*", RegexOption.IGNORE_CASE)
    for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        val line = raw.replace(Regex("[\\t \\u00a0]+"), " ").trim()
        val match = bullet.matchEntire(line)
        when {
            line.isEmpty() -> flush()
            match != null -> {
                flush()
                marker = match.groupValues[1].let { if (it == "-" || it == "*") "•" else it }
                lines += match.groupValues[2]
            }
            line.endsWith(':') && line.length < 90 -> {
                flush()
                blocks += DescriptionBlock(line, heading = true)
            }
            quantityLine.matches(line) -> {
                flush()
                blocks += DescriptionBlock(line)
            }
            else -> lines += line
        }
    }
    flush()
    return blocks
}
