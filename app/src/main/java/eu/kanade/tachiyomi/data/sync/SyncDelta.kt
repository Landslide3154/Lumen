package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import tachiyomi.domain.category.model.Category

/**
 * Keeps only manga (and their chapters) modified at or after the last successful upload, which is
 * all a SyncYomi protocol v2 server needs. `lastModifiedAt` is in epoch seconds, set by the
 * database triggers.
 *
 * A manga kept only because of a chapter change is sent with just its changed chapters; the server
 * treats a manga without chapters as "nothing to say about chapters", never "no chapters".
 *
 * Mutates the kept [BackupManga] instances (the backup models are plain classes); callers pass a
 * freshly built backup that is only used for the upload.
 */
internal fun changedSince(mangas: List<BackupManga>, since: Long): List<BackupManga> =
    mangas
        .filter { it.lastModifiedAt >= since || it.chapters.any { chapter -> chapter.lastModifiedAt >= since } }
        .onEach { manga -> manga.chapters = manga.chapters.filter { it.lastModifiedAt >= since } }

/** [BackupCategory] has identity equality, and local ids differ between devices. */
internal fun categoriesDiffer(local: List<BackupCategory>, remote: List<BackupCategory>): Boolean =
    local.map { it.syncIdentity() }.toSet() != remote.map { it.syncIdentity() }.toSet()

private fun BackupCategory.syncIdentity() = listOf(uid, name, order, flags, hidden, version)

internal fun extensionStoresDiffer(local: List<BackupExtensionStore>, remote: List<BackupExtensionStore>): Boolean =
    local.map { it.syncIdentity() }.toSet() != remote.map { it.syncIdentity() }.toSet()

private fun BackupExtensionStore.syncIdentity() =
    listOf(indexUrl, name, badgeLabel, signingKey, contactWebsite, contactDiscord, isLegacy, extensionListUrl)

/**
 * A response leaves out every section nothing changed in, so an empty list means "unchanged", never
 * "the remote has nothing now": only a non-empty list that differs is a change. Every section that
 * has no version of its own is compared this way, feeds included.
 */
internal fun <T> List<T>.changedFrom(local: List<T>): Boolean = isNotEmpty() && this != local

/**
 * Local categories the replica no longer lists, which means another device deleted them. Only ones
 * the upload carried count: a category created while the request was in flight is not on the server
 * yet, and removing it here would throw away what the user just made.
 */
internal fun categoriesDeletedRemotely(
    local: List<Category>,
    uploaded: List<BackupCategory>,
    remote: List<BackupCategory>,
): List<Category> {
    val uploadedUids = uploaded.map { it.uid }.toSet()
    val remoteUids = remote.map { it.uid }.toSet()
    val remoteNames = remote.map { it.name }.toSet()
    return local.filterNot(Category::isSystemCategory).filter {
        it.uid in uploadedUids && it.uid !in remoteUids && it.name !in remoteNames
    }
}

/** A backup with nothing in it cannot be applied, and the restore job cannot even read it. */
internal fun Backup.hasNothingToRestore(): Boolean = backupManga.isEmpty() &&
    backupCategories.isEmpty() &&
    backupSources.isEmpty() &&
    backupPreferences.isEmpty() &&
    backupSourcePreferences.isEmpty() &&
    backupExtensionStores.isEmpty() &&
    backupSavedSearches.isEmpty() &&
    backupFeeds.isEmpty()
