package nl.baskt.data

/** Recompute from downloaded prices so quantities, picks and assignments work without a server. */
fun localComparison(items: List<BasketItem>, stores: List<StoreInfo>, rankBy: String): Comparison {
    val active = items.filter { !it.isGroup && !it.checked }
    val products = active.flatMap { it.matches }.mapNotNull { it.effective }.associateBy { it.id }
    val rows = active.map { item ->
        val perStore = stores.filter { it.enabled }.associate { store ->
            val match = item.match(store.code)?.takeUnless { it.status == "NONE" }
            val product = match?.effective
            store.code to product?.let {
                val hint = item.parsed?.sizeHint
                val packs = if (hint != null && it.unitAmount != null && it.unitAmount > 0 && hint.unit == it.unit)
                    kotlin.math.ceil(hint.amount / it.unitAmount - 1e-9).toInt().coerceAtLeast(1) else 1
                val units = item.quantity.coerceAtLeast(1) * packs
                StoreLine(it.id, units, units * it.priceCents, it.unitPriceCents, it.unitPriceUnit, match.status == "CHOSEN")
            }
        }
        val present = perStore.filterValues { it != null }
        val cheapest = present.minByOrNull { it.value!!.lineCents }
        val unit = present.filterValues { it?.unitPriceCents != null && it.unitPriceUnit == cheapest?.value?.unitPriceUnit }.minByOrNull { it.value!!.unitPriceCents!! }
        ComparisonItem(item.id, item.text, cheapest?.key, unit?.key,
            present.values.map { it!!.unitsToBuy }.distinct().size > 1 || (unit != null && unit.key != cheapest?.key), perStore)
    }
    val everywhere = rows.filter { it.perStore.isNotEmpty() && it.perStore.values.all { line -> line != null } }
    val summaries = stores.filter { it.enabled }.map { store ->
        val lines = rows.mapNotNull { it.perStore[store.code] }
        StoreSummary(store.code, 0, lines.sumOf { it.lineCents }, everywhere.sumOf { it.perStore[store.code]!!.lineCents },
            lines.size, lines.count { !it.confirmed }, rows.filter { it.perStore[store.code] == null }.map { it.itemId })
    }.sortedWith(compareBy<StoreSummary> { it.comparableTotalCents }.thenBy { it.missingItemIds.size }.thenBy { it.fullTotalCents })
        .mapIndexed { index, summary -> summary.copy(rank = index + 1) }
    val order = mutableMapOf<String, OrderStoreTotal>()
    val unassigned = mutableListOf<String>()
    for (item in active) {
        val line = rows.first { it.itemId == item.id }.perStore[item.assignedStore]
        if (line == null || item.assignedStore == null) unassigned.add(item.id)
        else { val previous = order[item.assignedStore] ?: OrderStoreTotal(); order[item.assignedStore] = OrderStoreTotal(previous.totalCents + line.lineCents, previous.count + 1) }
    }
    return Comparison(summaries, rows, rows.sumOf { row -> row.perStore.values.filterNotNull().minOfOrNull { it.lineCents } ?: 0 },
        rows.filter { it.cheapestStore == null }.map { it.itemId }, System.currentTimeMillis(), rankBy, OrderSummary(order, unassigned), products)
}
