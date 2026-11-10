package nl.baskt.ui.basket

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import nl.baskt.ui.common.NavRow
import nl.baskt.ui.common.SheetBody

/**
 * Every way to share the basket in one place, behind the single "Share…" entry of the basket menu. Each
 * option has its own icon and starts with the same verb; picking one closes the sheet first, so the system
 * share dialog never opens on top of it.
 */
@Composable
fun ShareSheet(showBot: Boolean, onDismiss: () -> Unit, onLink: () -> Unit, onLinkWhatsApp: () -> Unit, onText: () -> Unit, onBot: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetBody(title = "Share basket", actions = { TextButton(onClick = onDismiss) { Text("Cancel") } }) {
            Column {
                ShareOption(Icons.Default.Link, "Share a link", "Others can open the basket and edit it with you") { onDismiss(); onLink() }
                ShareOption(Icons.AutoMirrored.Filled.Chat, "Share a link on WhatsApp", null) { onDismiss(); onLinkWhatsApp() }
                ShareOption(Icons.AutoMirrored.Filled.Notes, "Share as text", "The items per store, with prices") { onDismiss(); onText() }
                if (showBot) ShareOption(Icons.Default.SmartToy, "Share with the WhatsApp bot", "Check items off by reacting in the chat") { onDismiss(); onBot() }
            }
        }
    }
}

@Composable
private fun ShareOption(icon: ImageVector, title: String, body: String?, onClick: () -> Unit) {
    NavRow(title = title, body = body, leading = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = onClick)
}
