package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.Backup

/** One part of the library a sync carried. */
enum class SyncSection {
    MANGA,
    CHAPTER,
    CATEGORY,
    SETTING,
    SOURCE_SETTING,
    SAVED_SEARCH,
    FEED,
    EXTENSION_STORE,
}

/** How many entries of one [SyncSection] moved. */
data class SyncSectionCount(val section: SyncSection, val count: Int)

/** Keeps the sections that moved, in the order the notifications list them. */
internal fun syncSections(vararg counts: Pair<SyncSection, Int>): List<SyncSectionCount> =
    counts.map { SyncSectionCount(it.first, it.second) }.filter { it.count > 0 }

/**
 * What the upload carried.
 *
 * Categories always travel (the server wants the complete list), so in a delta only the sections
 * the response echoed back as changed count — otherwise a sync that changed nothing would report
 * the whole library as uploaded.
 */
internal fun uploadedSections(upload: Backup, remote: Backup, full: Boolean): List<SyncSectionCount> {
    fun moved(count: Int, differs: Boolean) = if (full || differs) count else 0

    return syncSections(
        SyncSection.MANGA to upload.backupManga.size,
        SyncSection.CHAPTER to upload.backupManga.sumOf { it.chapters.size },
        SyncSection.CATEGORY to moved(
            upload.backupCategories.size,
            categoriesDiffer(upload.backupCategories, remote.backupCategories),
        ),
        SyncSection.SETTING to moved(
            upload.backupPreferences.size,
            remote.backupPreferences.changedFrom(upload.backupPreferences),
        ),
        SyncSection.SOURCE_SETTING to moved(
            upload.backupSourcePreferences.size,
            remote.backupSourcePreferences.changedFrom(upload.backupSourcePreferences),
        ),
        SyncSection.SAVED_SEARCH to moved(
            upload.backupSavedSearches.size,
            remote.backupSavedSearches.changedFrom(upload.backupSavedSearches),
        ),
        SyncSection.FEED to moved(
            upload.backupFeeds.size,
            remote.backupFeeds.changedFrom(upload.backupFeeds),
        ),
        SyncSection.EXTENSION_STORE to moved(
            upload.backupExtensionStores.size,
            // a response leaves out the stores it kept, so an empty list means "unchanged" here too
            remote.backupExtensionStores.isNotEmpty() &&
                extensionStoresDiffer(upload.backupExtensionStores, remote.backupExtensionStores),
        ),
    )
}

/**
 * What one finished sync did, for the completion notification.
 *
 * @property uploaded what the request carried, changes only unless [full].
 * @property full true when the whole library was uploaded rather than the delta since the last sync.
 * @property trigger why this sync ran.
 * @property durationMs how long it took, measured by the caller.
 * @property remoteHadNothingNew the server had nothing to send back, so nothing was written here.
 * @property deletedCategories categories removed here because another device deleted them.
 */
data class SyncSummary(
    val uploaded: List<SyncSectionCount> = emptyList(),
    val full: Boolean = false,
    val trigger: SyncTrigger = SyncTrigger.UNKNOWN,
    val durationMs: Long = 0,
    val remoteHadNothingNew: Boolean = false,
    val deletedCategories: Int = 0,
)
