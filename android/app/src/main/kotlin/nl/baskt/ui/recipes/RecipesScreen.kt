package nl.baskt.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * your own and favourite recipes, and dishes you can cook from what is in stock.
 */
@Composable
fun RecipesScreen(viewModel: AppViewModel, onBack: () -> Unit, onFolderAdded: () -> Unit, onCreate: (String?) -> Unit) {
    val results by viewModel.recipeResults.collectAsState()
    val favourites by viewModel.recipeFavourites.collectAsState()
    val mine by viewModel.myRecipes.collectAsState()
    val stockDishes by viewModel.stockDishes.collectAsState()
    val detail by viewModel.recipeDetail.collectAsState()
    val busy by viewModel.recipesBusy.collectAsState()
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<RecipeSummary?>(null) }
    LaunchedEffect(Unit) { viewModel.loadRecipeFavourites(); viewModel.loadMyRecipes() }
    LaunchedEffect(tab) { if (tab == 2 && stockDishes == null) viewModel.loadStockDishes() }

    fun isFavourite(recipe: RecipeSummary) = favourites.any { it.url == recipe.url }
    fun submit() {
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
            ExtendedFloatingActionButton(onClick = { onCreate(null) }, icon = { Icon(Icons.Default.Add, contentDescription = null) }, text = { Text("New recipe") })
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 3)) { Text("Favourites (${mine.size + favourites.size})") }
                SegmentedButton(selected = tab == 2, onClick = { tab = 2 }, shape = SegmentedButtonDefaults.itemShape(2, 3)) { Text("From stock") }
            }
            if (busy) Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { LoadingIndicator(); Text(if (tab == 2) "Thinking about what you can cook…" else "Searching recipe sites…") }
            when (tab) {
                0 -> RecipeList(
                    list = results,
                    empty = "Type a dish, e.g. \"arroz cubano\" or \"iets met kip\". baskt understands it, searches Allerhande, Leuke Recepten, BBC Good Food, RecetasGratis, Cookpad and TheMealDB, and shows results in your language.",
                    busy = busy,
                    isFavourite = ::isFavourite,
                    onOpen = { selected = it; viewModel.openRecipe(it.url) },
                    onFavourite = { viewModel.toggleRecipeFavourite(it) },
                    onFolder = { viewModel.addRecipeFolder(it.url); onFolderAdded() },
                )
                1 -> MineList(mine, favourites.map { RecipeSummary(it.title, it.url, it.imageUrl, source = "Favourite") }, viewModel, onEdit = { onCreate(it.id) }, onOpen = { selected = it; viewModel.openRecipe(it.url) }, onFolder = { viewModel.addRecipeFolder(it); onFolderAdded() })
                else -> StockDishList(stockDishes ?: emptyList(), busy, onSearch = { query = it; tab = 0; viewModel.searchRecipes(it) }, onRefresh = { viewModel.loadStockDishes() })
            }
        }
    }

    val current = selected
    if (current != null) {
        RecipeSheet(title = current.displayTitle, detail = detail, onDismiss = { selected = null; viewModel.closeRecipe() }) {
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
                        Text(recipe.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
private fun MineList(mine: List<UserRecipe>, favourites: List<RecipeSummary>, viewModel: AppViewModel, onEdit: (UserRecipe) -> Unit, onOpen: (RecipeSummary) -> Unit, onFolder: (String) -> Unit) {
    if (mine.isEmpty() && favourites.isEmpty()) {
        Text("Your own recipes and favourites appear here. Tap \"New recipe\" to write one or let the AI draft it from a description.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mine.isNotEmpty()) item("h1") { Text("My recipes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        items(mine, key = { it.id }) { recipe ->
            Card(onClick = { onOpen(RecipeSummary(recipe.title, recipe.url, recipe.imageUrl, source = "Mine")) }) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (recipe.imageUrl != null) AsyncImage(model = recipe.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(recipe.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    Text(recipe.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { viewModel.toggleRecipeFavourite(recipe) }) { Icon(Icons.Default.Favorite, contentDescription = "Remove favourite", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { onFolder(recipe.url) }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Add as folder") }
                }
            }
        }
    }
}

@Composable
private fun StockDishList(dishes: List<nl.baskt.data.StockDish>, busy: Boolean, onSearch: (String) -> Unit, onRefresh: () -> Unit) {
    if (dishes.isEmpty() && !busy) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nothing yet. Add what you have at home under Stock, and baskt proposes dishes you can cook with it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRefresh) { Text("Try again") }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(dishes, key = { it.title }) { dish ->
            Card(onClick = { onSearch(dish.searchQuery) }) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(dish.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        dish.minutes?.let { Text("$it min", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (dish.uses.isNotEmpty()) Text("Uses: ${dish.uses.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (dish.missing.isEmpty()) AssistChip(onClick = {}, label = { Text("Everything in stock") })
                        else for (item in dish.missing.take(4)) AssistChip(onClick = {}, label = { Text("+ $item") })
                    }
                    TextButton(onClick = { onSearch(dish.searchQuery) }) { Text("Find recipes") }
                }
            }
        }
    }
}
