package nl.baskt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Durable recipe library. Saving a favourite also downloads its full cooking instructions. */
class RecipeLibrary(private val api: BasktApi, private val offline: OfflineStore, private val basket: BasketRepository, private val cacheImages: (List<String>) -> Unit = {}) {
    val mine = MutableStateFlow<List<UserRecipe>>(emptyList())
    val favourites = MutableStateFlow<List<RecipeFavourite>>(emptyList())
    /** Recipes opened on this device, newest first. Kept locally only, capped at [RECENT_LIMIT]. */
    val recent = MutableStateFlow<List<RecipeSummary>>(emptyList())

    fun reloadLocal() {
        mine.value = offline.load("my-recipes") ?: emptyList()
        favourites.value = offline.load("recipe-favourites") ?: emptyList()
        recent.value = offline.load("recipe-recent") ?: emptyList()
    }
    private fun persist() {
        offline.save("my-recipes", mine.value)
        offline.save("recipe-favourites", favourites.value)
        offline.save("recipe-recent", recent.value)
    }
    /** Moves [recipe] to the front of the recently viewed list. */
    fun recordViewed(recipe: RecipeSummary) {
        recent.update { list -> (listOf(recipe) + list.filterNot { it.url == recipe.url }).take(RECENT_LIMIT) }
        persist()
    }
    fun clearRecent() { recent.value = emptyList(); persist() }
    suspend fun refresh() {
        if (!basket.online.value || basket.pending.value.isNotEmpty()) return
        try {
            val version = basket.revision
            val recipes = api.myRecipes()
            val saved = api.recipeFavourites()
            if (basket.pending.value.isNotEmpty() || version != basket.revision) return
            mine.value = recipes
            favourites.value = saved
            persist()
            for (recipe in saved) {
                if (!basket.online.value) break
                detail(recipe.url)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep the local library if the server cannot answer. */ }
    }
    suspend fun detail(url: String): RecipeDetail? {
        if (url.startsWith("baskt://recipe/")) {
            mine.value.firstOrNull { it.url == url }?.let {
                return RecipeDetail(it.title, it.url, it.servings, it.ingredientLines, it.imageUrl, it.steps, description = it.description)
            }
        }
        val key = "recipe-detail-$url"
        val cached = offline.load<RecipeDetail>(key)
        if (cached != null && (cached.original != null || !basket.online.value)) {
            cacheImages(listOfNotNull(cached.imageUrl) + cached.steps.mapNotNull { it.imageUrl })
            return cached
        }
        if (!basket.online.value) return cached
        return try { api.fetchRecipe(url).also { offline.save(key, it); cacheImages(listOfNotNull(it.imageUrl) + it.steps.mapNotNull { step -> step.imageUrl }) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { cached }

    }
    suspend fun toggle(recipe: RecipeSummary) {
        val existing = favourites.value.firstOrNull { it.url == recipe.url }
        if (existing != null) {
            basket.queued(PendingOp(type = "favouriteDelete", itemId = existing.id), {
                favourites.update { list -> list.filterNot { it.id == existing.id } }; persist()
            })
        } else {
            val favourite = RecipeFavourite("local-${java.util.UUID.randomUUID()}", recipe.title, recipe.url, recipe.imageUrl, System.currentTimeMillis())
            basket.queued(PendingOp(type = "favouriteAdd", itemId = favourite.id, recipe = recipe), {
                favourites.update { it + favourite }; persist()
            })
            detail(recipe.url)
        }
    }
    suspend fun save(draft: RecipeDraft, origin: String, existingId: String? = null): UserRecipe {
        require(draft.title.isNotBlank() && draft.ingredientLines.isNotEmpty()) { "Title and ingredients are required" }
        val previous = mine.value.firstOrNull { it.id == existingId }
        val recipe = UserRecipe(existingId ?: "local-${java.util.UUID.randomUUID()}", draft.title, draft.description, draft.servings,
            draft.ingredientLines, draft.steps, previous?.imageUrl, previous?.sourceUrl, previous?.origin ?: origin,
            previous?.createdAt ?: System.currentTimeMillis(), System.currentTimeMillis())
        basket.queued(PendingOp(type = if (existingId == null) "recipeSave" else "recipeUpdate", itemId = recipe.id, draft = draft, mode = origin), {
            mine.update { list -> list.filterNot { it.id == recipe.id } + recipe }; persist()
        })
        return recipe
    }
    fun discardLocal(ids: Set<String>) {
        mine.update { list -> list.filterNot { it.id in ids } }
        favourites.update { list -> list.filterNot { it.id in ids } }
        recent.update { list -> list.filterNot { it.url.removePrefix("baskt://recipe/") in ids } }
        persist()
    }
    suspend fun delete(id: String) = basket.queued(PendingOp(type = "recipeDelete", itemId = id), {
        mine.update { list -> list.filterNot { it.id == id } }
        recent.update { list -> list.filterNot { it.url == "baskt://recipe/$id" } }
        persist()
    })

    private companion object { const val RECENT_LIMIT = 20 }
}
