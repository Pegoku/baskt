@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package nl.baskt.ui.basket

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.baskt.data.Deal
import nl.baskt.data.MergeDecision
import nl.baskt.data.StoreInfo
import nl.baskt.data.TidySelection
import nl.baskt.data.euros
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ProductRow
import nl.baskt.ui.common.StoreBadge

/**
 * Full-screen review of what the wand proposes: renames into the app language, merges of duplicates
 * nobody decided about, promotions to take. Every proposal starts ticked; untick to keep things as they
 * are. Nothing changes until Apply.
 *
 * Comment mode (toggle in the top bar, on by default): hold or tap proposals to select them, then write
 * what should change. The assistant revises only the selected proposals and remembers wording corrections.
 */
@Composable
fun TidyScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val plan by viewModel.tidy.collectAsState()
    val loading by viewModel.tidying.collectAsState()
    val revising by viewModel.revising.collectAsState()
    val commentMode by viewModel.tidyCommentMode.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    // The plan and the review state live in the view model: a rotation must neither refetch nor forget ticks.
    LaunchedEffect(Unit) { if (viewModel.tidy.value == null && !viewModel.tidying.value) viewModel.planTidy() }
    val review by viewModel.tidyReview.collectAsState()

    val renames = plan?.renames ?: emptyList()
    val merges = plan?.merges ?: emptyList()
    val deals = plan?.deals ?: emptyList()
    val distinct = plan?.distinct ?: emptyList()
    val skipped = review.skipped
    // Adding the amounts up is the default only when an entry already asked for more than one pack.
    val summed = review.summed ?: merges.filter { it.quantity > it.keepQuantity && it.items.any { item -> item.quantity > 1 } }.map { "merge-${it.keepId}" }.toSet()
    val allKeys = renames.map { "rename-${it.id}" } + merges.map { "merge-${it.keepId}" }
    // Promotions hang off the entry they belong to, most relevant first; none is chosen until the user picks one.
    val dealsByItem = deals.groupBy { it.itemId }
    val chosenRenames = renames.filter { "rename-${it.id}" !in skipped }
    val chosenMerges = merges.filter { "merge-${it.keepId}" !in skipped }.map { MergeDecision(it, sumQuantities = "merge-${it.keepId}" in summed) }
    val chosenDeals = review.dealChoice.mapNotNull { (itemId, productId) -> dealsByItem[itemId]?.firstOrNull { it.product.id == productId } }
    val changes = chosenRenames.size + chosenMerges.size + chosenDeals.size
    val dealOnlyItems = dealsByItem.keys.filter { id -> renames.none { it.id == id } && merges.none { it.keepId == id } }

    // Comment mode selection by proposal key; keys are stable across revisions.
    val selection = review.selected.intersect(allKeys.toSet())
    val allSelected = allKeys.isNotEmpty() && selection.size == allKeys.size
    val commenting = commentMode && !loading && allKeys.isNotEmpty()
    fun tick(key: String) = viewModel.updateTidyReview { it.copy(skipped = if (key in it.skipped) it.skipped - key else it.skipped + key) }
    fun select(key: String) = viewModel.updateTidyReview { it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key) }
    fun press(key: String) { if (commenting) select(key) else tick(key) }
    fun hold(key: String) { if (!commentMode) viewModel.toggleTidyCommentMode(); viewModel.updateTidyReview { it.copy(selected = it.selected + key) } }
    fun setSummed(key: String, add: Boolean) = viewModel.updateTidyReview { it.copy(summed = if (add) summed + key else summed - key) }
    fun chooseDeal(itemId: String, productId: String?) = viewModel.updateTidyReview { it.copy(dealChoice = if (productId == null) it.dealChoice - itemId else it.dealChoice + (itemId to productId)) }
    fun comment(text: String) {
        viewModel.reviseTidy(
            TidySelection(
                renames = renames.map { it.id }.filter { "rename-$it" in selection },
                merges = merges.map { it.keepId }.filter { "merge-$it" in selection },
            ),
            text,
        )
    }
    val dealPicker: @Composable (String) -> Unit = { itemId ->
        dealsByItem[itemId]?.let { list -> DealPicker(list, stores, chosen = review.dealChoice[itemId], onChoose = { chooseDeal(itemId, it) }) }
    }
    fun apply() {
        viewModel.applyTidy(chosenRenames, chosenMerges, chosenDeals)
        val parts = buildList {
            if (chosenRenames.isNotEmpty()) add(if (chosenRenames.size == 1) "renamed 1 item" else "renamed ${chosenRenames.size} items")
            val removed = chosenMerges.sumOf { it.merge.items.size - 1 }
            if (removed > 0) add(if (removed == 1) "merged 1 duplicate" else "merged $removed duplicates")
            if (chosenDeals.isNotEmpty()) add(if (chosenDeals.size == 1) "took 1 deal" else "took ${chosenDeals.size} deals")
        }
        if (parts.isNotEmpty()) viewModel.notify("Tidied up: " + parts.joinToString(", "))
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tidy up") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.Close, contentDescription = "Close") } },
                actions = {
                    if (commenting) {
                        IconButton(onClick = { viewModel.updateTidyReview { it.copy(selected = if (allSelected) emptySet() else allKeys.toSet()) } }) {
                            Icon(if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll, contentDescription = if (allSelected) "Unselect all" else "Select all")
                        }
                    }
                    if (!loading && allKeys.isNotEmpty()) {
                        IconToggleButton(checked = commentMode, onCheckedChange = { viewModel.toggleTidyCommentMode() }) {
                            Icon(if (commentMode) Icons.AutoMirrored.Filled.Comment else Icons.AutoMirrored.Outlined.Comment, contentDescription = if (commentMode) "Turn comment mode off" else "Turn comment mode on")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!loading && (allKeys.isNotEmpty() || dealOnlyItems.isNotEmpty())) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AnimatedVisibility(visible = commenting) { CommentBar(selectedCount = selection.size, busy = revising, onSend = { comment(it) }) }
                        Button(onClick = { apply() }, enabled = changes > 0 && !revising, modifier = Modifier.fillMaxWidth()) {
                            Text(if (changes == 0) "Nothing ticked" else if (changes == 1) "Apply 1 change" else "Apply $changes changes")
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            loading -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LoadingIndicator()
                    Text("Waving the wand over your list…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            allKeys.isEmpty() && dealOnlyItems.isEmpty() -> Box(modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (distinct.isNotEmpty()) "Nothing to change. The look-alikes each have their own product, so they stay."
                    else "Your list is already tidy: every entry reads well" + (plan?.language?.takeIf { it.isNotBlank() }?.let { " in $it" } ?: "") + ", nothing repeats and there are no new deals.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
                val reply = plan?.reply
                if (!reply.isNullOrBlank()) item { Text(reply, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
                item {
                    Text(
                        if (commenting) "Tap proposals to select them, then say what should change below." else "Untick anything you want to keep as it is.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (merges.isNotEmpty()) {
                    item { Subheader(if (merges.size == 1) "Merge duplicates" else "Merge ${merges.size} sets of duplicates") }
                    items(merges, key = { "merge-${it.keepId}" }) { merge ->
                        val key = "merge-${merge.keepId}"
                        val active = key !in skipped
                        Proposal(key, checked = active, selected = key in selection, onPress = { press(key) }, onHold = { hold(key) }, onTick = { tick(key) },
                            headline = merge.text,
                            supporting = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(merge.items.joinToString(" · ") { it.text + if (it.quantity > 1) " ×${it.quantity}" else "" } + if (merge.reason.isNotBlank()) "\n${merge.reason}" else "")
                                    if (active && !commenting) {
                                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                            SegmentedButton(selected = key !in summed, onClick = { setSummed(key, false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Keep one ×${merge.keepQuantity}") }
                                            SegmentedButton(selected = key in summed, onClick = { setSummed(key, true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Add up ×${merge.quantity}") }
                                        }
                                    }
                                    dealPicker(merge.keepId)
                                }
                            },
                        )
                    }
                }
                if (renames.isNotEmpty()) {
                    item { Subheader(if (renames.size == 1) "Rename" else "Rename ${renames.size} entries") }
                    items(renames, key = { "rename-${it.id}" }) { change ->
                        val key = "rename-${change.id}"
                        Proposal(key, checked = key !in skipped, selected = key in selection, onPress = { press(key) }, onHold = { hold(key) }, onTick = { tick(key) },
                            headline = change.to + (change.quantity?.let { "  ×$it" } ?: ""),
                            supporting = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val detail = listOfNotNull(if (change.from != change.to) "was “${change.from}”" else null, change.reason?.takeIf { it.isNotBlank() }).joinToString("\n")
                                    if (detail.isNotBlank()) Text(detail)
                                    dealPicker(change.id)
                                }
                            },
                        )
                    }
                }
                if (dealOnlyItems.isNotEmpty()) {
                    item { Subheader("Deals available") }
                    items(dealOnlyItems, key = { "deals-$it" }) { itemId ->
                        val list = dealsByItem.getValue(itemId)
                        ListItem(
                            leadingContent = { Icon(Icons.Outlined.LocalOffer, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(list.first().itemText) },
                            supportingContent = { dealPicker(itemId) },
                        )
                    }
                }
                if (distinct.isNotEmpty()) {
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                    item { Subheader("Kept apart") }
                    items(distinct, key = { "distinct-" + it.items.joinToString("+") { item -> item.id } }) { entry ->
                        ListItem(
                            leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            headlineContent = { Text(entry.items.joinToString(" · ") { it.text }) },
                            supportingContent = { Text(entry.items.mapNotNull { it.product }.joinToString("\n") + "\nLook alike, but each has its own chosen product.") },
                        )
                    }
                }
            }
        }
    }
}

/**
 * "3 deals available" for one entry, most relevant first. Tapping expands the list; a deal is only taken
 * when its radio is chosen, and choosing it again clears the choice.
 */
@Composable
private fun DealPicker(deals: List<Deal>, stores: List<StoreInfo>, chosen: String?, onChoose: (String?) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.LocalOffer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                when {
                    chosen != null -> "Deal chosen: " + (deals.firstOrNull { it.product.id == chosen }?.product?.title ?: "")
                    deals.size == 1 -> "1 deal available"
                    else -> "${deals.size} deals available"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Hide deals" else "Show deals", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                for (deal in deals) {
                    val selected = deal.product.id == chosen
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(if (selected) null else deal.product.id) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RadioButton(selected = selected, onClick = { onChoose(if (selected) null else deal.product.id) })
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StoreBadge(deal.store, stores)
                                val saving = deal.savingCents
                                if (saving != null && saving > 0) Text("save ${saving.euros()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            ProductRow(deal.product)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Subheader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** One proposal as a Material list item: checkbox to keep or drop it, selected colours in comment mode. */
@Composable
private fun Proposal(
    key: String,
    checked: Boolean,
    selected: Boolean,
    onPress: () -> Unit,
    onHold: () -> Unit,
    onTick: () -> Unit,
    headline: String,
    overline: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onPress, onLongClick = onHold),
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        leadingContent = { Checkbox(checked = checked, onCheckedChange = { onTick() }) },
        overlineContent = overline,
        headlineContent = { Text(headline) },
        supportingContent = supporting,
    )
}
