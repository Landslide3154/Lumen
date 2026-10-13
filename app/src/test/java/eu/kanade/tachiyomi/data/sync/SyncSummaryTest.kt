package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncSummaryTest {

    private fun manga(url: String, vararg chapters: BackupChapter) = BackupManga(
        source = 1,
        url = url,
        title = url,
        chapters = chapters.toList(),
    )

    private fun chapter(url: String) = BackupChapter(url = url, name = url)

    private fun category(name: String, uid: Long) = BackupCategory(name = name, uid = uid, version = 1)

    private fun preference(key: String) = BackupPreference(key = key, value = StringPreferenceValue())

    private fun extensionStore(indexUrl: String) = BackupExtensionStore(
        indexUrl = indexUrl,
        name = "Keiyoushi",
        signingKey = "key",
    )

    @Test
    fun `a full upload reports every section that carries something`() {
        val upload = Backup(
            backupManga = listOf(manga("/a", chapter("/a/1")), manga("/b")),
            backupCategories = listOf(category("Reading", uid = 1)),
            backupPreferences = listOf(preference("theme")),
            backupExtensionStores = listOf(extensionStore("https://example.com/index.min.json")),
        )

        val sections = uploadedSections(upload, remote = Backup(), full = true)

        assertEquals(
            listOf(
                SyncSectionCount(SyncSection.MANGA, 2),
                SyncSectionCount(SyncSection.CHAPTER, 1),
                SyncSectionCount(SyncSection.CATEGORY, 1),
                SyncSectionCount(SyncSection.SETTING, 1),
                SyncSectionCount(SyncSection.EXTENSION_STORE, 1),
            ),
            sections,
        )
    }

    @Test
    fun `a delta leaves out the sections the server has unchanged`() {
        val upload = Backup(
            backupManga = listOf(manga("/a")),
            backupCategories = listOf(category("Reading", uid = 1)),
            backupPreferences = listOf(preference("theme")),
        )
        // The response echoes a delta server's complete category list and nothing for settings.
        val remote = Backup(backupCategories = listOf(category("Reading", uid = 1)))

        assertEquals(
            listOf(SyncSectionCount(SyncSection.MANGA, 1)),
            uploadedSections(upload, remote = remote, full = false),
        )
    }

    @Test
    fun `a delta counts a section the server sent back different`() {
        val upload = Backup(backupPreferences = listOf(preference("theme")))
        val remote = Backup(backupPreferences = listOf(preference("locale")))

        assertEquals(
            listOf(SyncSectionCount(SyncSection.SETTING, 1)),
            uploadedSections(upload, remote = remote, full = false),
        )
    }

    @Test
    fun `a delta leaves out an extension store the server did not send back`() {
        val upload = Backup(backupExtensionStores = listOf(extensionStore("https://example.com/index.min.json")))

        assertEquals(
            emptyList<SyncSectionCount>(),
            uploadedSections(upload, remote = Backup(), full = false),
        )
    }

    @Test
    fun `sections keep their order and drop the ones that did not move`() {
        assertEquals(
            listOf(SyncSectionCount(SyncSection.MANGA, 3), SyncSectionCount(SyncSection.CATEGORY, 2)),
            syncSections(SyncSection.MANGA to 3, SyncSection.CHAPTER to 0, SyncSection.CATEGORY to 2),
        )
    }

    @Test
    fun `an unknown trigger key is reported as unknown`() {
        assertEquals(SyncTrigger.UNKNOWN, SyncTrigger.fromKey(null))
        assertEquals(SyncTrigger.UNKNOWN, SyncTrigger.fromKey("NOPE"))
        assertEquals(SyncTrigger.BEFORE_LIBRARY_UPDATE, SyncTrigger.fromKey("BEFORE_LIBRARY_UPDATE"))
    }
}
