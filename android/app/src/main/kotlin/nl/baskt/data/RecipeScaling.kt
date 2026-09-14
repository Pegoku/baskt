package nl.baskt.data

fun scaleIngredient(line: String, factor: Double): String {
    val fractions = mapOf("½" to 0.5, "¼" to 0.25, "¾" to 0.75, "⅓" to 1.0 / 3, "⅔" to 2.0 / 3)
    fun amount(raw: String): String {
        val scaled = (fractions[raw] ?: raw.replace(',', '.').toDouble()) * factor
        return java.math.BigDecimal.valueOf(scaled).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
    val regex = Regex("^\\s*(\\d+(?:[.,]\\d+)?|[½¼¾⅓⅔])(?:\\s*[-–]\\s*(\\d+(?:[.,]\\d+)?))?")
    val match = regex.find(line) ?: return line
    val replacement = amount(match.groupValues[1]) + match.groupValues[2].takeIf { it.isNotEmpty() }?.let { "-${amount(it)}" }.orEmpty()
    return line.replaceRange(match.range, replacement)
}
