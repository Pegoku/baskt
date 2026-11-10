@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package nl.baskt.ui.basket

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import nl.baskt.data.Deal
import nl.baskt.data.DealSaving
import nl.baskt.data.MergeDecision
import nl.baskt.data.Product
import nl.baskt.data.StoreInfo
import nl.baskt.data.TidySelection
import nl.baskt.data.euros
import nl.baskt.data.saving
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ChoiceGroup
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.ItemThumb
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.StoreBadge
import nl.baskt.ui.common.counted

/**
 * Full-screen review of what a tidy-up would change: renames into the app language, merges of duplicates
 * nobody decided about, deals to take. Every change starts ticked; untick one to leave things as they
 * are. Nothing changes until Apply.
 *
 * Each row has one selection, its checkbox, and tapping the row ticks it. Saying what is wrong with a change is
 * a separate, explicit action: the Comment button on the row opens the comment field for it (several rows can
 * be marked), and the marked changes are then revised.
 */
@Composable
fun TidyScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val plan by viewModel.tidy.collectAsState()
    val loading by viewModel.tidying.collectAsState()
    val failed by viewModel.tidyFailed.collectAsState()
    val revising by viewModel.revising.collectAsState()
    val stores by viewModel.basket.stores.collectAsState()
    // The plan only carries ids, so the pictures come from the items the basket already holds.
    val listed by viewModel.basket.items.collectAsState()
    val pictures = listed.associate { it.id to (it.anyProduct?.imageUrl ?: it.recipe?.imageUrl) }
    // The plan and the review state live in the view model: a rotation must neither refetch nor forget ticks.
    // Applying empties the plan, so the screen must not ask for a new one while that runs.
    var applying by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { if (viewModel.tidy.value == null && !viewModel.tidying.value && applying == null) viewModel.planTidy() }
    // Leave only once the changes were really made; the outcome message is posted at the same moment.
    LaunchedEffect(applying) { applying?.let { it.join(); onBack() } }
    val review by viewModel.tidyReview.collectAsState()

    val renames = plan?.renames ?: emptyList()
    val merges = plan?.merges ?: emptyList()
    val deals = plan?.deals ?: emptyList()
    val distinct = plan?.distinct ?: emptyList()
    val skipped = review.skipped
    // Adding the amounts up is the default only when an item already asked for more than one pack.
    val summed = review.summed ?: merges.filter { it.quantity > it.keepQuantity && it.items.any { item -> item.quantity > 1 } }.map { "merge-${it.keepId}" }.toSet()
    val allKeys = renames.map { "rename-${it.id}" } + merges.map { "merge-${it.keepId}" }
    // Deals hang off the item they belong to, most relevant first; none is chosen until the user picks one.
    val dealsByItem = deals.groupBy { it.itemId }
    val chosenRenames = renames.filter { "rename-${it.id}" !in skipped }
    val chosenMerges = merges.filter { "merge-${it.keepId}" !in skipped }.map { MergeDecision(it, sumQuantities = "merge-${it.keepId}" in summed) }
    val chosenDeals = review.dealChoice.mapNotNull { (itemId, productId) -> dealsByItem[itemId]?.firstOrNull { it.product.id == productId } }
    val changes = chosenRenames.size + chosenMerges.size + chosenDeals.size
    val dealOnlyItems = dealsByItem.keys.filter { id -> renames.none { it.id == id } && merges.none { it.keepId == id } }

    // The changes marked for a comment, by key; keys are stable across revisions.
    val commented = review.selected.intersect(allKeys.toSet())
    fun tick(key: String) = viewModel.updateTidyReview { it.copy(skipped = if (key in it.skipped) it.skipped - key else it.skipped + key) }
    fun markForComment(key: String) = viewModel.updateTidyReview { it.copy(selected = if (key in it.selected) it.selected - key else it.selected + key) }
    fun setSummed(key: String, add: Boolean) = viewModel.updateTidyReview { it.copy(summed = if (add) summed + key else summed - key) }
    fun chooseDeal(itemId: String, productId: String?) = viewModel.updateTidyReview { it.copy(dealChoice = if (productId == null) it.dealChoice - itemId else it.dealChoice + (itemId to productId)) }
    fun comment(text: String) {
        viewModel.reviseTidy(
            TidySelection(
                renames = renames.map { it.id }.filter { "rename-$it" in commented },
                merges = merges.map { it.keepId }.filter { "merge-$it" in commented },
            ),
            text,
        )
    }
    // What each item buys now at that store, so a deal can be priced against it per kilo or litre.
    fun currentPick(deal: Deal): Product? = listed.firstOrNull { it.id == deal.itemId }?.match(deal.store)?.effective
    val dealPicker: @Composable (String) -> Unit = { itemId ->
        dealsByItem[itemId]?.let { list ->
            DealPicker(
                list,
                stores,
                chosen = review.dealChoice[itemId],
                quantity = listed.firstOrNull { it.id == itemId }?.quantity ?: 1,
                currentFor = { deal -> currentPick(deal) },
                onChoose = { chooseDeal(itemId, it) },
            )
        }
    }
    fun apply() {
        val parts = buildList {
            if (chosenRenames.isNotEmpty()) add("renamed ${counted(chosenRenames.size, "item")}")
            val removed = chosenMerges.sumOf { it.merge.items.size - 1 }
            if (removed > 0) add("merged ${counted(removed, "duplicate")}")
            if (chosenDeals.isNotEmpty()) add("took ${counted(chosenDeals.size, "deal")}")
        }
        val job = viewModel.applyTidy(chosenRenames, chosenMerges, chosenDeals)
        // Report it when the work is done, not when the button was pressed (and not at all if it was cut short).
        job.invokeOnCompletion { cause -> if (cause == null && parts.isNotEmpty()) viewModel.notify("Tidied up: " + parts.joinToString(", ")) }
        applying = job
    }
    val commentLabel = when {
        commented.size != 1 -> "Comment on ${counted(commented.size, "change")}"
        else -> "Comment on “" + (merges.firstOrNull { "merge-${it.keepId}" in commented }?.text ?: renames.firstOrNull { "rename-${it.id}" in commented }?.to.orEmpty()) + "”"
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Tidy up") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        bottomBar = {
            if (!loading && applying == null && (allKeys.isNotEmpty() || dealOnlyItems.isNotEmpty())) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    // safeDrawing covers both the gesture bar and the keyboard, so Apply clears whichever is up.
                    Column(
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(horizontal = Spacing.Screen, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AnimatedVisibility(visible = commented.isNotEmpty()) {
                            CommentBar(label = commentLabel, busy = revising, onSend = { comment(it) }, onCancel = { viewModel.updateTidyReview { it.copy(selected = emptySet()) } })
                        }
                        Button(onClick = { apply() }, enabled = changes > 0 && !revising, modifier = Modifier.fillMaxWidth()) {
                            Text(if (changes == 0) "Apply" else "Apply ${counted(changes, "change")}")
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            loading || applying != null -> Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingRow(if (applying != null) "Applying…" else "Checking your basket…")
            }
            failed -> EmptyState(
                title = "Couldn’t check your basket",
                body = "Tidying up needs a connection.",
                icon = Icons.Outlined.CloudOff,
                modifier = Modifier.fillMaxSize().padding(padding),
                action = { FilledTonalButton(onClick = { viewModel.planTidy() }) { Text("Try again") } },
            )
            allKeys.isEmpty() && dealOnlyItems.isEmpty() -> EmptyState(
                title = "Nothing to tidy",
                body = if (distinct.isNotEmpty()) "Similar items each have their own product, so they stay separate." else "No renames, duplicates or new deals found.",
                icon = Icons.Default.CheckCircle,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
                val reply = plan?.reply
                if (!reply.isNullOrBlank()) item { Text(reply, modifier = Modifier.padding(horizontal = Spacing.Screen, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                // The one hint on this screen; the Comment button on each row explains itself.
                if (allKeys.isNotEmpty()) item {
                    Text("Untick a change to leave it out", modifier = Modifier.padding(horizontal = Spacing.Screen, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (merges.isNotEmpty()) {
                    item { Subheader("Duplicates") }
                    items(merges, key = { "merge-${it.keepId}" }) { merge ->
                        val key = "merge-${merge.keepId}"
                        val active = key !in skipped
                        Proposal(checked = active, commented = key in commented, enabled = !revising, onTick = { tick(key) }, onComment = { markForComment(key) },
                            headline = merge.text,
                            imageUrl = pictures[merge.keepId],
                            supporting = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Seeing the pictures side by side is the quickest way to tell a real duplicate from two different things.
                                    val thumbs = merge.items.mapNotNull { item -> pictures[item.id]?.let { item.text to it } }
                                    if (thumbs.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        for ((text, url) in thumbs) ItemThumb(url, text)
                                    }
                                    Text(merge.items.joinToString(" · ") { it.text + if (it.quantity > 1) " ×${it.quantity}" else "" } + if (merge.reason.isNotBlank()) "\n${merge.reason}" else "")
                                    if (active) ChoiceGroup(
                                        options = listOf("Keep one ×${merge.keepQuantity}", "Add up ×${merge.quantity}"),
                                        selected = if (key in summed) 1 else 0,
                                        onSelect = { setSummed(key, it == 1) },
                                    )
                                    dealPicker(merge.keepId)
                                }
                            },
                        )
                    }
                }
                if (renames.isNotEmpty()) {
                    item { Subheader("Renames") }
                    items(renames, key = { "rename-${it.id}" }) { change ->
                        val key = "rename-${change.id}"
                        Proposal(checked = key !in skipped, commented = key in commented, enabled = !revising, onTick = { tick(key) }, onComment = { markForComment(key) },
                            headline = change.to + (change.quantity?.let { " ×$it" } ?: ""),
                            imageUrl = pictures[change.id],
                            supporting = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val detail = listOfNotNull(if (change.from != change.to) "Was “${change.from}”" else null, change.reason?.takeIf { it.isNotBlank() }).joinToString("\n")
                                    if (detail.isNotBlank()) Text(detail)
                                    dealPicker(change.id)
                                }
                            },
                        )
                    }
                }
                if (dealOnlyItems.isNotEmpty()) {
                    item { Subheader("Deals") }
                    items(dealOnlyItems, key = { "deals-$it" }) { itemId ->
                        val list = dealsByItem.getValue(itemId)
                        ListItem(
                            leadingContent = { ItemThumb(pictures[itemId] ?: list.first().product.imageUrl, list.first().itemText) },
                            headlineContent = { Text(list.first().itemText) },
                            supportingContent = { dealPicker(itemId) },
                        )
                    }
                }
                if (distinct.isNotEmpty()) {
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                    item { Subheader("Kept separate") }
                    // Said once for the whole section instead of under every row.
                    item { Text("These look alike, but each has its own product.", modifier = Modifier.padding(horizontal = Spacing.Screen), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(distinct, key = { "distinct-" + it.items.joinToString("+") { item -> item.id } }) { entry ->
                        ListItem(
                            leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            headlineContent = { Text(entry.items.joinToString(" · ") { it.text }) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val thumbs = entry.items.mapNotNull { item -> pictures[item.id]?.let { item.text to it } }
                                    if (thumbs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        for ((text, url) in thumbs) ItemThumb(url, text)
                                    }
                                    val products = entry.items.mapNotNull { it.product }
                                    if (products.isNotEmpty()) Text(products.joinToString("\n"))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * "3 deals available" for one item, most relevant first. Tapping the line expands the options: "No deal"
 * (the default) and one row per deal. They are radio buttons, so exactly one is always chosen and tapping
 * anywhere on a row chooses it; nothing in a row opens another sheet.
 */
@Composable
private fun DealPicker(
    deals: List<Deal>,
    stores: List<StoreInfo>,
    chosen: String?,
    quantity: Int,
    currentFor: (Deal) -> Product?,
    onChoose: (String?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val allSavings = deals.associate { it.product.id to it.saving(currentFor(it)) }
    // A deal that costs more than what the item buys now is no deal for it, so it is not offered.
    val deals = deals.filter { !allSavings.getValue(it.product.id).worse }
    if (deals.isEmpty()) return
    val savings = allSavings.filterKeys { id -> deals.any { it.product.id == id } }
    val best = savings.values.mapNotNull { it.percentOff }.maxOrNull()
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = if (expanded) "Hide deals" else "Show deals") { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.LocalOffer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                when {
                    chosen != null -> "Deal chosen: " + (deals.firstOrNull { it.product.id == chosen }?.product?.title ?: "")
                    else -> "${counted(deals.size, "deal")} available" + (best?.let { if (deals.size == 1) " · save $it%" else " · save up to $it%" } ?: "")
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.selectableGroup()) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = chosen == null, role = Role.RadioButton, onClick = { onChoose(null) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = chosen == null, onClick = null)
                    Text("No deal", style = MaterialTheme.typography.bodyMedium)
                }
                for (deal in deals) {
                    val selected = deal.product.id == chosen
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = { onChoose(deal.product.id) }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(modifier = Modifier.weight(1f)) {
                            StoreBadge(deal.store, stores)
                            ProductLine(deal.product, modifier = Modifier.padding(top = 4.dp), dealMath = false)
                            val saving = savings.getValue(deal.product.id)
                            if (saving.worthShowing()) SavingStrip(saving, quantity)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The number the choice hangs on, made the headline: what a kilo, litre or pack costs now, struck through,
 * and what it comes to with the deal, with the share saved as a badge and the pack saving underneath.
 */
@Composable
private fun SavingStrip(saving: DealSaving, quantity: Int) {
    val step = saving.step()
    val suffix = step.unit?.let { "/$it" } ?: ""
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.Card, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    step.fromCents?.let { from ->
                        Text(
                            from.euros() + suffix,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textDecoration = TextDecoration.LineThrough,
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "becomes",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        step.toCents.euros() + suffix,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (step.unit == null) Text("a pack", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                saving.detail(quantity)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            saving.percentOff?.let { percent ->
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary) {
                    Text(
                        "−$percent%",
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Subheader(text: String) {
    SectionHeader(text, modifier = Modifier.padding(start = Spacing.Screen, end = Spacing.Screen, top = 16.dp, bottom = 4.dp))
}

/**
 * One proposed change as a Material list item. The row and its checkbox are one control: tapping anywhere
 * ticks or unticks the change. The Comment button at the end is the separate, explicit way to say what is
 * wrong with it; it stays lit while the change is marked for the comment being written.
 */
@Composable
private fun Proposal(
    checked: Boolean,
    commented: Boolean,
    enabled: Boolean,
    onTick: () -> Unit,
    onComment: () -> Unit,
    headline: String,
    imageUrl: String? = null,
    supporting: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = { onTick() }),
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Checkbox(checked = checked, onCheckedChange = null)
                ItemThumb(imageUrl, null)
            }
        },
        headlineContent = { Text(headline) },
        supportingContent = supporting,
        trailingContent = {
            TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text("Comment") } }, state = rememberTooltipState()) {
                IconToggleButton(checked = commented, onCheckedChange = { onComment() }, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Outlined.Comment, contentDescription = "Comment on $headline")
                }
            }
        },
    )
}
