package nl.baskt.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import nl.baskt.ui.AppViewModel

/** What you already have at home. Recipe folders skip these ingredients. */
@Composable
fun StockScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val stock by viewModel.basket.stock.collectAsState()
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.refreshStock() }
    fun submit() {
        val value = text.trim()
        if (value.isEmpty()) return
        value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { viewModel.addStock(it) }
        text = ""
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stock") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("salt, olive oil, rice…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                    FilledIconButton(onClick = { submit() }, enabled = text.isNotBlank()) { Icon(Icons.Default.Add, contentDescription = "Add") }
                }
            }
        },
    ) { padding ->
        if (stock.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("Nothing in stock yet", style = MaterialTheme.typography.titleMedium)
                Text("Add staples you always have (salt, pepper, oil, rice). Recipe folders will not ask you to buy them again.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(stock, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.text) },
                        supportingContent = item.quantityText?.let { { Text(it) } },
                        leadingContent = item.imageUrl?.let { url -> { coil3.compose.AsyncImage(model = url, contentDescription = null, modifier = Modifier.padding(2.dp).then(Modifier.size(44.dp))) } },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { viewModel.add(item.text, 1) }) { Icon(Icons.Default.AddShoppingCart, contentDescription = "Add to shopping list") }
                                IconButton(onClick = { viewModel.removeStock(item) }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                            }
                        },
                    )
                }
            }
        }
    }
}
