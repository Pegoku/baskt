package nl.baskt.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk snapshot of what the app last received from the server, plus the queue of changes made while
 * offline. Plain JSON files: the data is small and the server stays the source of truth.
 */
class OfflineStore(context: Context) {
    private val dir = File(context.filesDir, "offline").apply { mkdirs() }
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    inline fun <reified T> save(name: String, value: T) {
        val file = file(name)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(value))
        tmp.renameTo(file)
    }

    inline fun <reified T> load(name: String): T? {
        val file = file(name)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<T>(file.readText()) }.getOrNull()
    }

    fun file(name: String) = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    fun loadOps(): List<PendingOp> = load<List<PendingOp>>("pending-ops") ?: emptyList()
    fun saveOps(ops: List<PendingOp>) = save("pending-ops", ops)
}

/** One change made while offline; replayed in order when the server is back. Ids starting with "local-" are placeholders. */
@Serializable
data class PendingOp(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String,
    val basketId: String? = null,
    val itemId: String? = null,
    val text: String? = null,
    val quantity: Int? = null,
    val checked: Boolean? = null,
    val store: String? = null,
    val productId: String? = null,
    val items: List<String>? = null,
    val parentId: String? = null,
    val mode: String? = null,
    val copy: Boolean? = null,
    val at: Long = System.currentTimeMillis(),
) {
    val label: String
        get() = when (type) {
            "add" -> "Add “$text”"
            "addGroup" -> "Add folder “$text”"
            "checked" -> if (checked == true) "Check off" else "Uncheck"
            "quantity" -> "Set quantity $quantity"
            "rename" -> "Rename to “$text”"
            "delete" -> "Delete item"
            "deleteMany" -> "Delete ${items?.size ?: 0} items"
            "clearChecked" -> "Clear checked items"
            "choose" -> if (productId == null) "Skip store $store" else "Pick product at $store"
            "assign" -> "Assign store"
            "assignMode" -> "Assign stores ($mode)"
            "transfer" -> if (copy == true) "Copy to another basket" else "Move to another basket"
            "stockAdd" -> "Add “$text” to stock"
            "stockRemove" -> "Remove from stock"
            else -> type
        }
}
