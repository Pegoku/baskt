package nl.baskt.ui.recipes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import nl.baskt.data.DiscoverDish
import nl.baskt.data.DiscoverParams
import nl.baskt.data.RecipeSummary
import nl.baskt.data.UserRecipe
import nl.baskt.ui.AppViewModel
import nl.baskt.ui.common.ChoiceGroup
import nl.baskt.ui.common.ConfirmDialog
import nl.baskt.ui.common.DropdownPicker
import nl.baskt.ui.common.EmptyState
import nl.baskt.ui.common.IconLabel
import nl.baskt.ui.common.ItemThumb
import nl.baskt.ui.common.LoadingRow
import nl.baskt.ui.common.OfflineBanner
import nl.baskt.ui.common.SearchField
import nl.baskt.ui.common.SectionHeader
import nl.baskt.ui.common.Spacing
import nl.baskt.ui.common.Tag
import nl.baskt.ui.common.TranslatedLabel
import nl.baskt.ui.common.counted

/** Keeps the open recipe across a rotation: its title, address, picture and where it came from. */
private val RecipeSummarySaver = listSaver<RecipeSummary?, String>(
    save = { recipe -> if (recipe == null) emptyList() else listOf(recipe.title, recipe.url, recipe.imageUrl.orEmpty(), recipe.titleLocalized.orEmpty(), recipe.source.orEmpty()) },
    restore = { saved -> if (saved.size < 5) null else RecipeSummary(saved[0], saved[1], saved[2].ifEmpty { null }, titleLocalized = saved[3].ifEmpty { null }, source = saved[4].ifEmpty { null }) },
)

private fun RecipeSummary.isOwn() = url.startsWith("baskt://")

/**
 * Meal browser in three views. Find searches several recipe sites at once (the dish is understood and
 * translated first); Library holds your own recipes, favourites and recently viewed ones; Discover suggests
 * dishes from your stock, something new, or a country's cuisine. [onViewBasket] leaves for the basket; it is
 * only called from the "View" button of the notice that follows adding a recipe.
 */
@Composable
fun RecipesScreen(viewModel: AppViewModel, onBack: () -> Unit, onFolderAdded: () -> Unit, onCreate: (String?) -> Unit) {
    val onViewBasket = onFolderAdded
    val results by viewModel.recipeResults.collectAsState()
    val favourites by viewModel.recipeFavourites.collectAsState()
    val mine by viewModel.myRecipes.collectAsState()
    val recent by viewModel.recentRecipes.collectAsState()
    val discoverParams by viewModel.discoverParams.collectAsState()
    val discoverDishes by viewModel.discoverDishes.collectAsState()
    val discoverByTime by viewModel.discoverByTime.collectAsState()
    val discoverBusy by viewModel.discoverBusy.collectAsState()
    val detail by viewModel.recipeDetail.collectAsState()
    val busy by viewModel.recipesBusy.collectAsState()
    val online by viewModel.online.collectAsState()
    val searchMessage by viewModel.recipeMessage.collectAsState()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    // What the Find view was last searched with, for its "Try again".
    var searched by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable(stateSaver = RecipeSummarySaver) { mutableStateOf<RecipeSummary?>(null) }
    var openExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.loadRecipeFavourites(); viewModel.loadMyRecipes() }
    // Library recipes are downloaded and translated in the background, so tapping one opens it ready.
    LaunchedEffect(mine, favourites, recent) {
        viewModel.warmRecipes(mine.map { RecipeSummary(it.title, it.url, it.imageUrl) } + recent + favourites.map { RecipeSummary(it.title, it.url, it.imageUrl) })
    }
    // The open recipe is loaded here, so it also comes back when the app was closed with the sheet open.
    LaunchedEffect(selected?.url) { selected?.let { if (viewModel.recipeDetail.value == null) viewModel.openRecipe(it) } }
    // The "ask or change" field: questions asked and a proposed change, kept while the same recipe stays open.
    var recipeChat by remember(selected?.url) { mutableStateOf(emptyList<nl.baskt.data.RecipeTurn>()) }
    var recipeChange by remember(selected?.url) { mutableStateOf<RecipeChangePreview?>(null) }
    LaunchedEffect(tab, discoverParams) { if (tab == 2) viewModel.loadDiscover() }

    val link = query.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
    fun isFavourite(recipe: RecipeSummary) = favourites.any { it.url == recipe.url }
    fun open(recipe: RecipeSummary, expanded: Boolean = false) { selected = recipe; openExpanded = expanded }
    fun close() { selected = null; viewModel.closeRecipe() }
    fun search(value: String) {
        if (!online) { viewModel.notify("Recipe search needs a connection"); return }
        searched = value; tab = 0; viewModel.searchRecipes(value)
    }
    fun submit() {
        val value = query.trim()
        // A pasted link is not a search: the row under the field adds it.
        if (value.isNotEmpty() && link == null) search(value)
    }
    /** Adds the recipe's ingredients as a folder and stays here; the notice offers the way to the basket. */
    fun addToBasket(url: String, title: String?) {
        scope.launch {
            val folders = viewModel.basket.items.value.count { it.isGroup }
            viewModel.addRecipeFolder(url).join()
            // A failed add reports itself, so only a new folder is announced.
            if (viewModel.basket.items.value.count { it.isGroup } > folders) viewModel.notify(if (title != null) "Added “$title” to your basket" else "Added the recipe to your basket", "View") { onViewBasket() }
        }
    }
    fun toggleFavourite(recipe: RecipeSummary) {
        val wasFavourite = isFavourite(recipe)
        viewModel.toggleRecipeFavourite(recipe)
        if (wasFavourite) viewModel.notifyUndo("Removed “${recipe.displayTitle}” from favourites") { viewModel.toggleRecipeFavourite(recipe) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumFlexibleTopAppBar(
                scrollBehavior = scrollBehavior,
                title = { Text("Recipes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onCreate(null) }, icon = { Icon(Icons.Default.Add, contentDescription = null) }, text = { Text("New recipe") })
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(viewModel)
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "A dish, or a recipe link",
                onSearch = { submit() },
                modifier = Modifier.padding(horizontal = Spacing.Screen, vertical = 8.dp),
                leadingIcon = if (link != null) Icons.Default.Link else Icons.Default.Search,
            )
            if (link != null) Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Screen).padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { addToBasket(link, null); query = "" }) { IconLabel(Icons.Default.AddShoppingCart, "Add this recipe to the basket") }
            }
            val libraryCount = (mine.map { it.url } + favourites.map { it.url } + recent.map { it.url }).distinct().size
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Find", if (libraryCount > 0) "Library · $libraryCount" else "Library", "Discover").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            when (tab) {
                0 -> FindList(
                    list = results,
                    busy = busy,
                    message = searchMessage,
                    canRetry = searched.isNotBlank(),
                    loadingMore = viewModel.recipesLoadingMore.collectAsState().value,
                    hasMore = viewModel.recipesHasMore.collectAsState().value,
                    onLoadMore = { viewModel.loadMoreRecipes() },
                    onRetry = { search(searched) },
                    isFavourite = ::isFavourite,
                    onOpen = { open(it) },
                    onFavourite = ::toggleFavourite,
                    onAdd = { addToBasket(it.url, it.displayTitle) },
                )
                1 -> LibraryList(
                    mine = mine,
                    favourites = favourites.map { RecipeSummary(it.title, it.url, it.imageUrl, source = "Favourite") },
                    recent = recent,
                    viewModel = viewModel,
                    isFavourite = ::isFavourite,
                    onEdit = { onCreate(it.id) },
                    onOpen = { open(it) },
                    onFavourite = ::toggleFavourite,
                    onAdd = { addToBasket(it.url, it.displayTitle) },
                )
                else -> DiscoverPane(
                    params = discoverParams,
                    dishes = discoverDishes,
                    byTime = discoverByTime,
                    busy = discoverBusy,
                    onParams = { viewModel.setDiscoverParams(it) },
                    onByTime = { viewModel.setDiscoverByTime(it) },
                    onSearch = { query = it; search(it) },
                    onOpenDish = { dish ->
                        // The photo's recipe opens right away, full height; the other sites load behind it with this one on top.
                        val recipe = RecipeSummary(dish.title, dish.recipeUrl!!, dish.imageUrl, source = dish.source)
                        open(recipe, expanded = true)
                        query = dish.searchQuery; searched = dish.searchQuery; tab = 0; viewModel.searchRecipes(dish.searchQuery, pinned = recipe)
                    },
                    onRefresh = { viewModel.loadDiscover(force = true) },
                )
            }
        }
    }

    val current = selected
    if (current != null) {
        RecipeSheet(
            title = current.displayTitle, detail = detail, expanded = openExpanded, onDismiss = { close() },
            extra = {
                RecipeAiPanel(
                    detail = detail, viewModel = viewModel,
                    chat = recipeChat, onChat = { recipeChat = it },
                    preview = recipeChange, onPreview = { recipeChange = it },
                    savesCopy = !current.isOwn(),
                    onApply = { draft ->
                        // A site recipe's changed copy opens in its place.
                        viewModel.applyRecipeChange(current, draft)?.also { selected = it } != null
                    },
                )
            },
        ) {
            if (!current.isOwn()) {
                FavouriteToggle(isFavourite(current)) { toggleFavourite(current) }
                // The copy shrinks first on a narrow screen, so the main button always shows its whole label.
                TextButton(enabled = detail != null, onClick = { viewModel.saveSiteRecipeAsMine(current); viewModel.notify("Copied “${current.displayTitle}” to My recipes"); close() }, modifier = Modifier.weight(1f, fill = false)) {
                    Text("Copy to my recipes", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Button(onClick = { addToBasket(current.url, current.displayTitle); close() }) { IconLabel(Icons.Default.AddShoppingCart, "Add to basket") }
        }
    }
}

/** The heart of a recipe: filled when it is a favourite, in the toggle's own colours everywhere. */
@Composable
private fun FavouriteToggle(favourite: Boolean, onToggle: () -> Unit) {
    IconToggleButton(checked = favourite, onCheckedChange = { onToggle() }) {
        Icon(if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = if (favourite) "Remove from favourites" else "Add to favourites")
    }
}

/** The one "add this recipe's ingredients" button of a card; the sheet has the same action with its label. */
@Composable
private fun AddToBasketButton(onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), tooltip = { PlainTooltip { Text("Add ingredients to basket") } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick) { Icon(Icons.Default.AddShoppingCart, contentDescription = "Add ingredients to basket") }
    }
}

/** A recipe's photo at card size; without one, the shared placeholder tile so every title starts at the same place. */
@Composable
private fun RecipeThumb(imageUrl: String?) {
    if (imageUrl != null) AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.medium))
    else nl.baskt.ui.common.PhotoPlaceholder(64)
}

/** A recipe in a list: photo, title, one line of facts, and whatever the list puts at the end. */
@Composable
private fun RecipeCard(title: String, subtitle: String?, imageUrl: String?, onOpen: () -> Unit, trailing: @Composable () -> Unit) {
    Card(onClick = onOpen) {
        Row(modifier = Modifier.padding(start = Spacing.Card, top = Spacing.Card, bottom = Spacing.Card, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RecipeThumb(imageUrl)
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                TranslatedLabel(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
    }
}

@Composable
private fun FindList(
    list: List<RecipeSummary>, busy: Boolean, message: String?, canRetry: Boolean, loadingMore: Boolean, hasMore: Boolean,
    onLoadMore: () -> Unit, onRetry: () -> Unit, isFavourite: (RecipeSummary) -> Boolean, onOpen: (RecipeSummary) -> Unit, onFavourite: (RecipeSummary) -> Unit, onAdd: (RecipeSummary) -> Unit,
) {
    if (list.isEmpty()) {
        when {
            busy -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingRow("Searching recipe sites…") }
            message != null -> EmptyState("No recipes found", message, icon = Icons.AutoMirrored.Filled.MenuBook, modifier = Modifier.fillMaxSize()) {
                if (canRetry) FilledTonalButton(onClick = onRetry) { Text("Try again") }
            }
            else -> EmptyState("Find a recipe", "Type a dish in any language, or paste a recipe link", icon = Icons.Default.Search, modifier = Modifier.fillMaxSize())
        }
        return
    }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    // Ask for the next page while a few screens of results are still ahead, so a steady scroll never waits.
    val nearEnd by remember(list.size) {
        androidx.compose.runtime.derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.size - 8 }
    }
    LaunchedEffect(nearEnd, hasMore, list.size) { if (nearEnd && hasMore && list.isNotEmpty()) onLoadMore() }
    LazyColumn(state = state, contentPadding = PaddingValues(Spacing.Screen, 12.dp, Spacing.Screen, Spacing.FabClearance), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
        // A Discover photo is shown at once; the other sites are still being searched behind it.
        if (busy) item("searching") { LoadingRow("Searching recipe sites…") }
        items(list, key = { it.url }) { recipe ->
            RecipeCard(recipe.displayTitle, listOfNotNull(recipe.source, recipe.title.takeIf { it != recipe.displayTitle }).joinToString(" · "), recipe.imageUrl, onOpen = { onOpen(recipe) }) {
                FavouriteToggle(isFavourite(recipe)) { onFavourite(recipe) }
                AddToBasketButton { onAdd(recipe) }
            }
        }
        if (loadingMore || (hasMore && list.isNotEmpty())) item("more") {
            // Shown when the scroll outruns the next page.
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
        }
    }
}

@Composable
private fun LibraryList(
    mine: List<UserRecipe>, favourites: List<RecipeSummary>, recent: List<RecipeSummary>, viewModel: AppViewModel,
    isFavourite: (RecipeSummary) -> Boolean, onEdit: (UserRecipe) -> Unit, onOpen: (RecipeSummary) -> Unit, onFavourite: (RecipeSummary) -> Unit, onAdd: (RecipeSummary) -> Unit,
) {
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    mine.firstOrNull { it.id == deletingId }?.let { recipe ->
        ConfirmDialog(
            title = "Delete this recipe?",
            body = "“${recipe.title}” is deleted from My recipes.",
            confirmLabel = "Delete",
            onDismiss = { deletingId = null },
            onConfirm = { viewModel.deleteMyRecipe(recipe.id) },
        )
    }
    if (mine.isEmpty() && favourites.isEmpty() && recent.isEmpty()) {
        EmptyState("No recipes yet", "Your own recipes, favourites and recently viewed ones show up here", icon = Icons.AutoMirrored.Filled.MenuBook, modifier = Modifier.fillMaxSize())
        return
    }
    LazyColumn(contentPadding = PaddingValues(Spacing.Screen, 12.dp, Spacing.Screen, Spacing.FabClearance), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
        if (mine.isNotEmpty()) item("h1") { SectionHeader("My recipes") }
        items(mine, key = { it.id }) { recipe ->
            val summary = RecipeSummary(recipe.title, recipe.url, recipe.imageUrl, source = "My recipes")
            val facts = listOfNotNull(
                counted(recipe.ingredientLines.size, "ingredient"),
                recipe.servings?.takeIf { it.isNotBlank() }?.let { servingsLabel(it) },
                when (recipe.origin) { "ai" -> "Written by baskt"; "site" -> "Copied from a site"; else -> null },
            ).joinToString(" · ")
            RecipeCard(recipe.title, facts, recipe.imageUrl, onOpen = { onOpen(summary) }) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More for ${recipe.title}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit(recipe) })
                        DropdownMenuItem(text = { Text("Add to basket") }, onClick = { menu = false; onAdd(summary) })
                        DropdownMenuItem(text = { Text("Delete…") }, onClick = { menu = false; deletingId = recipe.id })
                    }
                }
            }
        }
        if (favourites.isNotEmpty()) item("h2") { SectionHeader("Favourites", Modifier.padding(top = 8.dp)) }
        items(favourites, key = { "fav-" + it.url }) { recipe ->
            RecipeCard(recipe.title, null, recipe.imageUrl, onOpen = { onOpen(recipe) }) {
                FavouriteToggle(true) { onFavourite(recipe) }
                AddToBasketButton { onAdd(recipe) }
            }
        }
        if (recent.isNotEmpty()) item("h3") {
            SectionHeader("Recently viewed", Modifier.padding(top = 8.dp)) {
                TextButton(onClick = {
                    val removed = recent
                    viewModel.clearRecentRecipes()
                    viewModel.notifyUndo("Removed ${counted(removed.size, "recently viewed recipe")}") { viewModel.restoreRecentRecipes(removed) }
                }) { Text("Remove all") }
            }
        }
        items(recent, key = { "recent-" + it.url }) { recipe ->
            RecipeCard(recipe.displayTitle, if (recipe.source == "Mine") "My recipes" else recipe.source, recipe.imageUrl, onOpen = { onOpen(recipe) }) {
                if (!recipe.url.startsWith("baskt://recipe/")) FavouriteToggle(isFavourite(recipe)) { onFavourite(recipe) }
                AddToBasketButton { onAdd(recipe) }
            }
        }
    }
}

/** How much of a dish must be at home, strictest first: the server's key, the option's label, and what it means. */
private val COVERAGE_LEVELS = listOf(
    Triple("all", "All at home", "Only dishes that need nothing extra"),
    Triple("most", "Most", "One or two small things may be missing"),
    Triple("half", "Half", "You buy the rest"),
)
private val DISCOVER_MODES = listOf("stock" to "From stock", "new" to "New ideas", "cuisine" to "By country")
private val NEW_STYLES = listOf("Vegetarian", "Vegan", "Healthy", "Comfort food", "Fish", "Spicy", "One pot")
private val CUISINES = listOf("Italy", "Spain", "Mexico", "Japan", "India", "Thailand", "Greece", "Morocco", "France", "Netherlands", "Turkey", "Peru")
private val TIME_LIMITS = listOf<Int?>(null, 15, 30, 45, 60)

/**
 * Discover: pick a mode (cook from stock, something new, a country's cuisine), tune its own parameter,
 * cap the total time and order by best match or by speed.
 */
@Composable
private fun DiscoverPane(
    params: DiscoverParams,
    dishes: List<DiscoverDish>?,
    byTime: Boolean,
    busy: Boolean,
    onParams: (DiscoverParams) -> Unit,
    onByTime: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onOpenDish: (DiscoverDish) -> Unit,
    onRefresh: () -> Unit,
) {
    val shown = remember(dishes, byTime) {
        val list = (dishes ?: emptyList()).distinctBy { it.title }
        if (byTime) list.sortedWith(compareBy(nullsLast()) { it.minutes }) else list
    }
    LazyColumn(contentPadding = PaddingValues(Spacing.Screen, 12.dp, Spacing.Screen, Spacing.FabClearance), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap)) {
        item("mode") {
            ChoiceGroup(DISCOVER_MODES.map { it.second }, DISCOVER_MODES.indexOfFirst { it.first == params.mode }.coerceAtLeast(0), onSelect = { onParams(params.copy(mode = DISCOVER_MODES[it].first)) })
        }
        item("params") {
            when (params.mode) {
                "stock" -> CoverageChoice(params.coverage) { onParams(params.copy(coverage = it)) }
                "new" -> Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = params.style == null, onClick = { onParams(params.copy(style = null)) }, label = { Text("Surprise me") })
                    for (style in NEW_STYLES) FilterChip(selected = params.style == style, onClick = { onParams(params.copy(style = if (params.style == style) null else style)) }, label = { Text(style) })
                }
                else -> CuisinePicker(params.cuisine) { onParams(params.copy(cuisine = it)) }
            }
        }
        item("time") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownPicker("Time", TIME_LIMITS, params.maxMinutes, optionLabel = { if (it == null) "Any time" else "Up to $it min" }, onPick = { onParams(params.copy(maxMinutes = it)) }, modifier = Modifier.weight(1f), placeholder = "Any time")
                DropdownPicker("Sort by", listOf(false, true), byTime, optionLabel = { if (it) "Quickest first" else "Best match" }, onPick = onByTime, modifier = Modifier.weight(1f))
            }
        }
        if (busy) item("busy") {
            LoadingRow(
                when (params.mode) { "stock" -> "Thinking about what you can cook…"; "new" -> "Looking for something new…"; else -> "Picking classics from ${params.cuisine}…" },
                Modifier.padding(vertical = 24.dp),
            )
        } else if (params.complete && dishes != null && dishes.isEmpty()) item("empty") {
            EmptyState(
                title = "No ideas yet",
                body = when {
                    params.mode == "stock" -> "Add more to your stock, or ask for less of it to be at home"
                    params.maxMinutes != null -> "Nothing fits in ${params.maxMinutes} minutes"
                    else -> "Nothing came back this time"
                },
                icon = Icons.Default.Restaurant,
            ) { FilledTonalButton(onClick = onRefresh) { Text("Try again") } }
        }
        items(shown, key = { it.title }) { dish -> DishCard(dish, onSearch, onOpenDish) }
        if (!busy && shown.isNotEmpty()) item("refresh") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onRefresh) { IconLabel(Icons.Default.Refresh, "Other ideas") } }
        }
    }
}

/** How much of a dish has to be at home already: three options, with one line saying what the chosen one means. */
@Composable
private fun CoverageChoice(coverage: String, onCoverage: (String) -> Unit) {
    val index = COVERAGE_LEVELS.indexOfFirst { it.first == coverage }.coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("In stock", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceGroup(COVERAGE_LEVELS.map { it.second }, index, onSelect = { onCoverage(COVERAGE_LEVELS[it].first) })
        Text(COVERAGE_LEVELS[index].third, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A country or cuisine: common ones as chips, anything else typed in. */
@Composable
private fun CuisinePicker(cuisine: String?, onCuisine: (String?) -> Unit) {
    var draft by rememberSaveable(cuisine) { mutableStateOf(cuisine.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchField(value = draft, onValueChange = { draft = it }, placeholder = "Country or cuisine, e.g. Mexico", onSearch = { onCuisine(draft.trim().ifEmpty { null }) }, leadingIcon = Icons.Default.Public)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in CUISINES) FilterChip(selected = cuisine.equals(name, ignoreCase = true), onClick = { onCuisine(name) }, label = { Text(name) })
        }
    }
}

/**
 * A dish idea. One with a pictured recipe is a card you tap to open that recipe; one without is not tappable
 * and carries a "Find recipes" button instead, so what a card does always shows.
 */
@Composable
private fun DishCard(dish: DiscoverDish, onSearch: (String) -> Unit, onOpen: (DiscoverDish) -> Unit) {
    val content: @Composable () -> Unit = {
        if (dish.imageUrl != null) {
            AsyncImage(model = dish.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(140.dp))
        }
        Column(modifier = Modifier.padding(Spacing.Card), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslatedLabel(dish.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                dish.minutes?.let { Text("$it min", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (dish.source != null && dish.imageUrl != null) Text("Photo from ${dish.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (dish.uses.isNotEmpty()) Text("From your stock: ${dish.uses.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dish.missing.isEmpty()) Tag("Everything in stock")
                else {
                    for (item in dish.missing.take(3)) Tag("Need $item", color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (dish.missing.size > 3) Tag("+${dish.missing.size - 3} more", color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (dish.recipeUrl == null) Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { onSearch(dish.searchQuery) }) { Text("Find recipes") }
            }
        }
    }
    if (dish.recipeUrl != null) Card(onClick = { onOpen(dish) }) { content() } else Card { content() }
}
