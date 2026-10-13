package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CategoriesRestorer(
    // KMK -->
    private val isSync: Boolean = false,
    // KMK <--
    private val handler: DatabaseHandler = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) {

    suspend operator fun invoke(backupCategories: List<BackupCategory>) {
        if (backupCategories.isEmpty()) return

        val dbCategories = getCategories.await()
        val restored = planCategoryRestore(backupCategories, dbCategories, isSync).map { plan ->
            when (plan) {
                is CategoryRestore.Keep -> plan.existing
                is CategoryRestore.Insert -> insert(plan.backupCategory, plan.order)
                is CategoryRestore.Merge -> merge(plan.existing, plan.restored)
            }
        }

        libraryPreferences.categorizedDisplaySettings().set(
            (dbCategories + restored)
                .distinctBy { it.flags }
                .size > 1,
        )
    }

    // KMK -->
    private suspend fun insert(backupCategory: BackupCategory, order: Long): Category =
        handler.awaitOneExecutable(inTransaction = true) {
            categoriesQueries.insert(
                name = backupCategory.name,
                order = order,
                flags = backupCategory.flags,
                hidden = if (backupCategory.hidden) 1L else 0L,
                version = backupCategory.version,
                uid = backupCategory.uid,
            )
            categoriesQueries.selectLastInsertedRowId()
        }
            .let { id -> backupCategory.toCategory(id).copy(order = order) }

    /**
     * Applies the replica's copy onto the row with the same identity. The version travels with it so
     * both sides agree on what has been seen; the database version trigger adds one more when the
     * name or the position really changed, which is harmless: the other device adopts that value
     * and stops there, because rewriting identical values bumps nothing.
     */
    private suspend fun merge(existing: Category, restored: Category): Category {
        handler.await(inTransaction = true) {
            categoriesQueries.update(
                name = restored.name,
                order = restored.order,
                flags = restored.flags,
                hidden = if (restored.hidden) 1L else 0L,
                version = restored.version,
                uid = restored.uid,
                categoryId = existing.id,
            )
        }
        return restored
    }
    // KMK <--
}

// KMK -->
/** What one entry of a restore does to the categories table. */
internal sealed interface CategoryRestore {
    /** Create the category, at [order]. */
    data class Insert(val backupCategory: BackupCategory, val order: Long) : CategoryRestore

    /** The replica's copy of a row that already exists; renames and reorders land here. */
    data class Merge(val existing: Category, val restored: Category) : CategoryRestore

    /** The local row already is the replica's copy. */
    data class Keep(val existing: Category) : CategoryRestore
}

/**
 * Maps every category a restore carries onto the row it belongs to.
 *
 * A sync is a converging replica, not an import: the server keys categories by uid and merges them
 * by version, so a rename or a reorder has to update the row that uid belongs to. Matching by name
 * alone would insert a second row carrying the same uid and leave the stale one behind forever.
 */
internal fun planCategoryRestore(
    backupCategories: List<BackupCategory>,
    dbCategories: List<Category>,
    isSync: Boolean,
): List<CategoryRestore> {
    val byUid = if (isSync) dbCategories.filterNot { it.uid == 0L }.associateBy { it.uid } else emptyMap()
    val byName = dbCategories.associateBy { it.name }
    var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0

    return backupCategories.sortedBy { it.order }.map { backupCategory ->
        val existing = byUid[backupCategory.uid] ?: byName[backupCategory.name]
        when {
            // A sync keeps the position the server holds, or reorders never converge.
            existing == null -> CategoryRestore.Insert(
                backupCategory = backupCategory,
                order = if (isSync) backupCategory.order else nextOrder++,
            )

            !isSync -> CategoryRestore.Keep(existing)

            else -> {
                val restored = backupCategory.toCategory(existing.id)
                if (restored == existing) {
                    CategoryRestore.Keep(existing)
                } else {
                    CategoryRestore.Merge(existing, restored)
                }
            }
        }
    }
}
// KMK <--
