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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import nl.baskt.ui.AppViewModel

/** Meal browser: search Allerhande, keep favourites, paste a recipe link; each becomes a folder. */
@Composable
fun RecipesScreen(viewModel: AppViewModel, onBack: () -> Unit, onFolderAdded: () -> Unit) {
    val results by viewModel.recipeResults.collectAsState()
    val favourites by viewModel.recipeFavourites.collectAsState()
    val detail by viewModel.recipeDetail.collectAsState()
    val busy by viewModel.recipesBusy.collectAsState()
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<RecipeSummary?>(null) }
    LaunchedEffect(Unit) { viewModel.loadRecipeFavourites() }

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
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Search a dish, or paste a recipe link") },
                singleLine = true,
                leadingIcon = { Icon(if (query.startsWith("http")) Icons.Default.Link else Icons.Default.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                trailingIcon = { IconButton(onClick = { submit() }) { Icon(Icons.Default.Search, contentDescription = "Search") } },
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SegmentedButton(selected = tab == 0, onClick = { tab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Results") }
                SegmentedButton(selected = tab == 1, onClick = { tab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Favourites (${favourites.size})") }
            }
            if (busy) Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { LoadingIndicator(); Text("Loading…") }
            val list = if (tab == 0) results else favourites.map { RecipeSummary(it.title, it.url, it.imageUrl) }
            if (list.isEmpty() && !busy) {
                Text(
                    if (tab == 0) "Search a dish (e.g. lasagne, pannenkoeken) to see recipes from Allerhande. Every recipe can be added to the basket as a folder with its ingredients."
                    else "No favourites yet. Tap the heart on a recipe to keep it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.url }) { recipe ->
                    Card(onClick = { selected = recipe; viewModel.openRecipe(recipe.url) }) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (recipe.imageUrl != null) {
                                AsyncImage(model = recipe.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                            }
                            Text(recipe.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { viewModel.toggleRecipeFavourite(recipe) }) {
                                Icon(if (isFavourite(recipe)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favourite", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { viewModel.addRecipeFolder(recipe.url); onFolderAdded() }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Add as folder") }
                        }
                    }
                }
            }
        }
    }

    val current = selected
    if (current != null) {
        ModalBottomSheet(onDismissRequest = { selected = null; viewModel.closeRecipe() }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(detail?.title ?: current.title, style = MaterialTheme.typography.titleLarge)
                detail?.servings?.let { Text("$it servings", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (detail == null) LoadingIndicator() else for (line in detail!!.ingredientLines) Text("• $line", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { viewModel.addRecipeFolder(current.url); selected = null; viewModel.closeRecipe(); onFolderAdded() }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                        Text("  Add as folder")
                    }
                    IconButton(onClick = { viewModel.toggleRecipeFavourite(current) }) {
                        Icon(if (isFavourite(current)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favourite")
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            }
        }
    }
}
