package nl.baskt.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk snapshot of what the app last received from the server, plus the queue of changes made while
 * offline. Plain JSON files: the data is small and the server stays the source of truth.
 */
class OfflineStore(private val dir: File, private val namespace: () -> String = { "default" }) {
    constructor(context: Context, namespace: () -> String = { "default" }) : this(File(context.filesDir, "offline"), namespace)
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    inline fun <reified T> save(name: String, value: T) {
        val file = file(name)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(value))
        check(tmp.renameTo(file)) { "Could not persist offline data" }
    }

    inline fun <reified T> load(name: String): T? {
        val file = file(name)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<T>(file.readText()) }.getOrNull()
    }

    fun file(name: String): File {
        fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val folder = File(dir, hash(namespace())).apply { mkdirs() }
        return File(folder, hash(name) + ".json")
    }

    /** Upgrade the previous single-server cache once, after the saved server settings are loaded. */
    fun migrateLegacy() {
        dir.listFiles()?.filter { it.isFile && it.extension == "json" }?.forEach { old ->
            val target = file(old.nameWithoutExtension)
            val destination = if (target.exists()) File(old.parentFile, old.name + ".migrated") else target
            check(old.renameTo(destination)) { "Could not migrate offline data" }
        }
    }

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
    val scaledChildren: List<ScaledIngredient>? = null,
    val recipeInfo: RecipeInfo? = null,
    val receipt: ReceiptScan? = null,
    val draft: RecipeDraft? = null,
    val recipe: RecipeSummary? = null,
    val stock: StockItem? = null,
    val emoji: String? = null,
    val childIds: List<String>? = null,
    val failure: String? = null,
    /** Rename without re-matching (tidy relabels). */
    val keepMatches: Boolean? = null,
    val at: Long = System.currentTimeMillis(),
) {
    val label: String
        get() = when (type) {
            "feedback" -> if (checked == true) "Thumbs up at $store" else "Thumbs down at $store"
            "recipeSave", "recipeUpdate" -> "Save recipe “${draft?.title}”"
            "recipeDelete" -> "Delete recipe"
            "favouriteAdd" -> "Save favourite “${recipe?.title}”"
            "favouriteDelete" -> "Remove favourite"
            "basketCreate" -> "Create basket “$text”"
            "basketRename" -> "Rename basket to “$text”"
            "basketDelete" -> "Delete basket"
            "productAdd" -> "Add product"
            "barcodeAdd" -> "Add scanned barcode $text"
            "purchaseSave" -> "Save purchase"
            "purchaseDelete" -> "Delete purchase"
            "memoryDelete" -> "Forget preference"
            "chatClear" -> "Clear chat history"
            "unskip" -> "Restore store $store"
            "groupItems" -> "Create folder “$text”"
            "skipStock", "servings", "rankBy", "stores", "language" -> "Update settings"
            "groupServings" -> "Set recipe servings"
            "add" -> "Add “$text”"
            "addGroup" -> "Add folder “$text”"
            "checked" -> if (checked == true) "Skip item" else "Unskip item"
            "bought" -> "Bought at $store"
            "unbuy" -> "Undo bought"
            "scanItem" -> "Scanned $text at $store"
            "quantity" -> "Set quantity $quantity"
            "rename" -> "Rename to “$text”"
            "delete" -> "Delete item"
            "deleteMany" -> "Delete ${items?.size ?: 0} items"
            "reorder" -> "Reorder items"
            "clearChecked" -> "Delete skipped items"
            "choose" -> if (productId == null) "Skip store $store" else "Pick product at $store"
            "assign" -> "Assign store"
            "assignMode" -> "Assign stores ($mode)"
            "transfer" -> if (copy == true) "Copy to another basket" else "Move to another basket"
            "stockAdd" -> "Add “$text” to stock"
            "stockRemove" -> "Remove from stock"
            else -> type
        }
}
