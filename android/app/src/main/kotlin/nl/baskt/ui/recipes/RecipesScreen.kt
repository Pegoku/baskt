package nl.baskt.ui.recipes

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import nl.baskt.data.RecipeSummary
import nl.baskt.data.UserRecipe
import nl.baskt.ui.AppViewModel

/**
 * Meal browser: search several recipe sites at once (the dish is understood and translated first),
 * your library (own recipes, favourites and recently viewed), and Discover: ideas from your stock, something new, or a country's cuisine.
 */
@Composable
fun RecipesScreen(viewModel: AppViewModel, onBack: () -> Unit, onFolderAdded: () -> Unit, onCreate: (String?) -> Unit) {
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
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<RecipeSummary?>(null) }
    var openExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.loadRecipeFavourites(); viewModel.loadMyRecipes() }
    LaunchedEffect(tab, discoverParams) { if (tab == 2) viewModel.loadDiscover() }

    fun isFavourite(recipe: RecipeSummary) = favourites.any { it.url == recipe.url }
    fun submit() {
        if (!online) { viewModel.notify("Recipe discovery needs a connection"); return }
        val value = query.trim()
        if (value.isEmpty()) return
        if (value.startsWith("http://") || value.startsWith("https://")) {
            viewModel.addRecipeFolder(value); onFolderAdded()
        } else {
            tab = 0; viewModel.searchRecipes(value)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Recipes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            // While something is typed, the button becomes the search (or link import) action.
            val typing = query.isNotBlank()
            val isLink = query.trim().startsWith("http://") || query.trim().startsWith("https://")
            ExtendedFloatingActionButton(
                onClick = { if (typing) submit() else onCreate(null) },
                icon = { Icon(if (!typing) Icons.Default.Add else if (isLink) Icons.Default.Link else Icons.Default.Search, contentDescription = null) },
                text = { Text(if (!typing) "New recipe" else if (isLink) "Add as folder" else "Search") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            nl.baskt.ui.common.OfflineBanner(viewModel, needsServer = "Searching recipes needs the server; favourites and your recipes are available.")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("A dish in any language, or a recipe link") },
                singleLine = true,
                leadingIcon = { Icon(if (query.startsWith("http")) Icons.Default.Link else Icons.Default.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                trailingIcon = { IconButton(onClick = { submit() }) { Icon(Icons.Default.Search, contentDescription = "Search") } },
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 3)) { Text("Search") }
                SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 3)) { Text("Library (${mine.size + favourites.size})") }
                SegmentedButton(selected = tab == 2, onClick = { tab = 2 }, shape = SegmentedButtonDefaults.itemShape(2, 3)) { Text("Discover") }
            }
            if (busy) Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { LoadingIndicator(); Text("Searching recipe sites…") }
            when (tab) {
                0 -> RecipeList(
                    list = results,
                    empty = searchMessage ?: "Type a dish, e.g. \"arroz cubano\" or \"iets met kip\". baskt understands it, searches Allerhande, Leuke Recepten, BBC Good Food, RecetasGratis, Cookpad and TheMealDB, and shows results in your language.",
                    busy = busy,
                    isFavourite = ::isFavourite,
                    onOpen = { selected = it; openExpanded = false; viewModel.openRecipe(it) },
                    onFavourite = { viewModel.toggleRecipeFavourite(it) },
                    onFolder = { viewModel.addRecipeFolder(it.url); onFolderAdded() },
                )
                1 -> MineList(mine, favourites.map { RecipeSummary(it.title, it.url, it.imageUrl, source = "Favourite") }, recent, viewModel, isFavourite = ::isFavourite, onEdit = { onCreate(it.id) }, onOpen = { selected = it; openExpanded = false; viewModel.openRecipe(it) }, onFolder = { viewModel.addRecipeFolder(it); onFolderAdded() })
                else -> DiscoverPane(
                    params = discoverParams,
                    dishes = discoverDishes,
                    byTime = discoverByTime,
                    busy = discoverBusy,
                    onParams = { viewModel.setDiscoverParams(it) },
                    onByTime = { viewModel.setDiscoverByTime(it) },
                    onSearch = { query = it; tab = 0; viewModel.searchRecipes(it) },
                    onOpenDish = { dish ->
                        // The photo's recipe opens right away, full height; the other sites load behind it with this one on top.
                        val recipe = RecipeSummary(dish.title, dish.recipeUrl!!, dish.imageUrl, source = dish.source)
                        selected = recipe; openExpanded = true; viewModel.openRecipe(recipe)
                        query = dish.searchQuery; tab = 0; viewModel.searchRecipes(dish.searchQuery, pinned = recipe)
                    },
                    onRefresh = { viewModel.loadDiscover(force = true) },
                )
            }
        }
    }

    val current = selected
    if (current != null) {
        RecipeSheet(title = current.displayTitle, detail = detail, expanded = openExpanded, onDismiss = { selected = null; viewModel.closeRecipe() }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.addRecipeFolder(current.url); selected = null; viewModel.closeRecipe(); onFolderAdded() }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                    Text("  Add as folder")
                }
                if (!current.url.startsWith("baskt://")) {
                    IconButton(onClick = { viewModel.toggleRecipeFavourite(current) }) { Icon(if (isFavourite(current)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favourite") }
                    TextButton(onClick = { viewModel.saveSiteRecipeAsMine(current); selected = null; viewModel.closeRecipe() }) { Text("Copy to mine") }
                }
            }
        }
    }
}

@Composable
private fun RecipeList(list: List<RecipeSummary>, empty: String, busy: Boolean, isFavourite: (RecipeSummary) -> Boolean, onOpen: (RecipeSummary) -> Unit, onFavourite: (RecipeSummary) -> Unit, onFolder: (RecipeSummary) -> Unit) {
    if (list.isEmpty() && !busy) {
        Text(empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 128.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.url }) { recipe ->
            Card(onClick = { onOpen(recipe) }) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (recipe.imageUrl != null) AsyncImage(model = recipe.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                    Column(modifier = Modifier.weight(1f)) {
                        nl.baskt.ui.common.TranslatedLabel(recipe.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(recipe.source, recipe.title.takeIf { it != recipe.displayTitle }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onFavourite(recipe) }) { Icon(if (isFavourite(recipe)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favourite", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { onFolder(recipe) }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Add as folder") }
                }
            }
        }
    }
}

@Composable
private fun MineList(mine: List<UserRecipe>, favourites: List<RecipeSummary>, recent: List<RecipeSummary>, viewModel: AppViewModel, isFavourite: (RecipeSummary) -> Boolean, onEdit: (UserRecipe) -> Unit, onOpen: (RecipeSummary) -> Unit, onFolder: (String) -> Unit) {
    if (mine.isEmpty() && favourites.isEmpty() && recent.isEmpty()) {
        Text("Your own recipes, favourites and recently viewed recipes appear here. Tap \"New recipe\" to write one or let the AI draft it from a description.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mine.isNotEmpty()) item("h1") { Text("My recipes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        items(mine, key = { it.id }) { recipe ->
            Card(onClick = { onOpen(RecipeSummary(recipe.title, recipe.url, recipe.imageUrl, source = "Mine")) }) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (recipe.imageUrl != null) AsyncImage(model = recipe.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                    Column(modifier = Modifier.weight(1f)) {
                        nl.baskt.ui.common.TranslatedLabel(recipe.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull("${recipe.ingredientLines.size} ingredients", recipe.servings?.let { "$it servings" }, when (recipe.origin) { "ai" -> "written by AI"; "site" -> "copied from a site"; else -> null }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onEdit(recipe) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                    IconButton(onClick = { onFolder(recipe.url) }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Add as folder") }
                    IconButton(onClick = { viewModel.deleteMyRecipe(recipe.id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                }
            }
        }
        if (favourites.isNotEmpty()) item("h2") { Text("Favourites from sites", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        items(favourites, key = { "fav-" + it.url }) { recipe ->
            Card(onClick = { onOpen(recipe) }) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (recipe.imageUrl != null) AsyncImage(model = recipe.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                    nl.baskt.ui.common.TranslatedLabel(recipe.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { viewModel.toggleRecipeFavourite(recipe) }) { Icon(Icons.Default.Favorite, contentDescription = "Remove favourite", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { onFolder(recipe.url) }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Add as folder") }
                }
            }
        }
        if (recent.isNotEmpty()) item("h3") {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recently viewed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = { viewModel.clearRecentRecipes() }) { Text("Clear") }
            }
        }
        items(recent, key = { "recent-" + it.url }) { recipe ->
            val own = recipe.url.startsWith("baskt://recipe/")
            Card(onClick = { onOpen(recipe) }) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (recipe.imageUrl != null) AsyncImage(model = recipe.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                    Column(modifier = Modifier.weight(1f)) {
                        nl.baskt.ui.common.TranslatedLabel(recipe.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (recipe.source != null) Text(recipe.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!own) IconButton(onClick = { viewModel.toggleRecipeFavourite(recipe) }) { Icon(if (isFavourite(recipe)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favourite", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { onFolder(recipe.url) }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Add as folder") }
                }
            }
        }
    }
}

/** The three coverage levels of the "from stock" slider, from strictest to loosest. */
private val COVERAGE_LEVELS = listOf(
    Triple("all", "Everything at home", "Only dishes that need nothing extra."),
    Triple("most", "Most at home", "One or two small things may be missing."),
    Triple("half", "At least half at home", "Buy the rest on the way."),
)
private val NEW_STYLES = listOf("Vegetarian", "Vegan", "Healthy", "Comfort food", "Fish", "Spicy", "One pot")
private val CUISINES = listOf("Italy", "Spain", "Mexico", "Japan", "India", "Thailand", "Greece", "Morocco", "France", "Netherlands", "Turkey", "Peru")
private val TIME_LIMITS = listOf(15, 30, 45, 60)

/**
 * Discover: pick a mode (cook from stock, something new, a country's cuisine), tune its own parameters,
 * cap the total time and order by best match or by speed.
 */
@Composable
private fun DiscoverPane(
    params: nl.baskt.data.DiscoverParams,
    dishes: List<nl.baskt.data.DiscoverDish>?,
    byTime: Boolean,
    busy: Boolean,
    onParams: (nl.baskt.data.DiscoverParams) -> Unit,
    onByTime: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onOpenDish: (nl.baskt.data.DiscoverDish) -> Unit,
    onRefresh: () -> Unit,
) {
    val shown = remember(dishes, byTime) {
        val list = (dishes ?: emptyList()).distinctBy { it.title }
        if (byTime) list.sortedWith(compareBy(nullsLast()) { it.minutes }) else list
    }
    LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("mode") {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = params.mode == "stock", onClick = { onParams(params.copy(mode = "stock")) }, label = { Text("From stock") }, leadingIcon = { Icon(Icons.Default.Kitchen, contentDescription = null, modifier = Modifier.size(18.dp)) })
                FilterChip(selected = params.mode == "new", onClick = { onParams(params.copy(mode = "new")) }, label = { Text("Something new") }, leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)) })
                FilterChip(selected = params.mode == "cuisine", onClick = { onParams(params.copy(mode = "cuisine")) }, label = { Text("By country") }, leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp)) })
            }
        }
        item("params") {
            when (params.mode) {
                "stock" -> CoverageSlider(params.coverage) { onParams(params.copy(coverage = it)) }
                "new" -> Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = params.style == null, onClick = { onParams(params.copy(style = null)) }, label = { Text("Surprise me") })
                    for (style in NEW_STYLES) FilterChip(selected = params.style == style, onClick = { onParams(params.copy(style = if (params.style == style) null else style)) }, label = { Text(style) })
                }
                else -> CuisinePicker(params.cuisine) { onParams(params.copy(cuisine = it)) }
            }
        }
        item("time") {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Schedule, contentDescription = "Time", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(selected = params.maxMinutes == null, onClick = { onParams(params.copy(maxMinutes = null)) }, label = { Text("Any time") })
                for (limit in TIME_LIMITS) FilterChip(selected = params.maxMinutes == limit, onClick = { onParams(params.copy(maxMinutes = if (params.maxMinutes == limit) null else limit)) }, label = { Text("≤ $limit min") })
                FilterChip(selected = byTime, onClick = { onByTime(!byTime) }, label = { Text("Quickest first") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp)) })
            }
        }
        if (busy) item("busy") {
            Row(modifier = Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator()
                Text(when (params.mode) { "stock" -> "Thinking about what you can cook…"; "new" -> "Looking for something you have not tried…"; else -> "Picking classics from ${params.cuisine}…" })
            }
        } else if (!params.complete) item("hint") {
            Text("Pick or type a country to see its classics.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        } else if (dishes != null && dishes.isEmpty()) item("empty") {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        params.mode == "stock" -> "Nothing fits yet. Add what you have at home under Stock, loosen the slider, or allow more time."
                        params.maxMinutes != null -> "Nothing fits in ${params.maxMinutes} minutes. Allow more time or try another mode."
                        else -> "No ideas came back. Try again in a moment."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRefresh) { Text("Try again") }
            }
        }
        items(shown, key = { it.title }) { dish -> DishCard(dish, onSearch, onOpenDish) }
        if (!busy && shown.isNotEmpty()) item("refresh") {
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)); Text("  Other ideas") }
        }
    }
}

/** Three-step slider: everything at home, most at home, or at least half at home. */
@Composable
private fun CoverageSlider(coverage: String, onCoverage: (String) -> Unit) {
    val index = COVERAGE_LEVELS.indexOfFirst { it.first == coverage }.coerceAtLeast(0)
    var position by remember(index) { mutableFloatStateOf(index.toFloat()) }
    val level = COVERAGE_LEVELS[position.toInt().coerceIn(0, COVERAGE_LEVELS.lastIndex)]
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("How much must be in stock?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(level.second, style = MaterialTheme.typography.titleMedium)
            Text(level.third, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = position,
                onValueChange = { position = it },
                onValueChangeFinished = { onCoverage(COVERAGE_LEVELS[position.toInt().coerceIn(0, COVERAGE_LEVELS.lastIndex)].first) },
                valueRange = 0f..COVERAGE_LEVELS.lastIndex.toFloat(),
                steps = COVERAGE_LEVELS.size - 2,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("All", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Most", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Half", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A country or cuisine: common ones as chips, anything else typed in. */
@Composable
private fun CuisinePicker(cuisine: String?, onCuisine: (String?) -> Unit) {
    var draft by remember(cuisine) { mutableStateOf(cuisine.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("A country or cuisine, e.g. Mexico or Sichuan") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Public, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCuisine(draft.trim().ifEmpty { null }) }),
            trailingIcon = { if (draft.isNotBlank()) IconButton(onClick = { onCuisine(draft.trim().ifEmpty { null }) }) { Icon(Icons.Default.Check, contentDescription = "Use this country") } },
        )
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in CUISINES) FilterChip(selected = cuisine.equals(name, ignoreCase = true), onClick = { onCuisine(name) }, label = { Text(name) })
        }
    }
}

@Composable
private fun DishCard(dish: nl.baskt.data.DiscoverDish, onSearch: (String) -> Unit, onOpen: (nl.baskt.data.DiscoverDish) -> Unit) {
    // Tapping the card opens the pictured recipe itself when there is one; otherwise it just searches the dish.
    Card(onClick = { if (dish.recipeUrl != null) onOpen(dish) else onSearch(dish.searchQuery) }) {
        if (dish.imageUrl != null) {
            AsyncImage(model = dish.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(140.dp))
        }
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                nl.baskt.ui.common.TranslatedLabel(dish.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                dish.minutes?.let { Text("$it min", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (dish.source != null) Text("Photo: ${dish.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (dish.uses.isNotEmpty()) Text("Uses: ${dish.uses.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dish.missing.isEmpty()) AssistChip(onClick = {}, label = { Text("Everything in stock") })
                else for (item in dish.missing.take(4)) AssistChip(onClick = {}, label = { Text("+ $item") })
            }
            TextButton(onClick = { onSearch(dish.searchQuery) }) { Text("Find recipes") }
        }
    }
}
