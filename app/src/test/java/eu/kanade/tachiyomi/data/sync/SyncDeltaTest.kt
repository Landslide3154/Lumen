package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

class SyncDeltaTest {

    private fun manga(url: String, modifiedAt: Long, vararg chapters: BackupChapter) = BackupManga(
        source = 1,
        url = url,
        title = url,
        lastModifiedAt = modifiedAt,
        chapters = chapters.toList(),
    )

    private fun chapter(url: String, modifiedAt: Long) = BackupChapter(
        url = url,
        name = url,
        lastModifiedAt = modifiedAt,
    )

    @Test
    fun `keeps only what changed since the last upload`() {
        val mangas = listOf(
            manga("/old", 10, chapter("/old/1", 10)),
            manga("/touched", 50, chapter("/touched/1", 10), chapter("/touched/2", 50)),
            manga("/chapter-only", 10, chapter("/chapter-only/1", 60)),
            manga("/boundary", 40),
        )

        val delta = changedSince(mangas, since = 40)

        assertEquals(listOf("/touched", "/chapter-only", "/boundary"), delta.map { it.url })
        assertEquals(listOf("/touched/2"), delta[0].chapters.map { it.url })
        assertEquals(listOf("/chapter-only/1"), delta[1].chapters.map { it.url })
        assertTrue(delta[2].chapters.isEmpty())
    }

    @Test
    fun `everything is changed since zero`() {
        val mangas = listOf(manga("/a", 0, chapter("/a/1", 0)), manga("/b", 5))

        assertEquals(2, changedSince(mangas, since = 0).size)
        assertEquals(1, changedSince(mangas, since = 0)[0].chapters.size)
    }

    @Test
    fun `categories compare by content, not identity`() {
        val local = listOf(BackupCategory(name = "Reading", order = 0, id = 3, uid = 7, version = 1))
        val same = listOf(BackupCategory(name = "Reading", order = 0, id = 9, uid = 7, version = 1))

        assertFalse(categoriesDiffer(local, same))
        assertTrue(categoriesDiffer(local, listOf(BackupCategory(name = "Renamed", order = 0, uid = 7))))
        assertTrue(categoriesDiffer(local, listOf(BackupCategory(name = "Reading", order = 1, uid = 7))))
        // a different uid with the same name is a different category on the server
        assertTrue(categoriesDiffer(local, listOf(BackupCategory(name = "Reading", order = 0, uid = 8))))
        assertTrue(categoriesDiffer(local, emptyList()))
    }

    // A v2 delta response can carry categories but no manga; that must decode as an empty list.
    @Test
    fun `decodes a response without manga`() {
        val bytes = ProtoBuf.encodeToByteArray(
            Backup.serializer(),
            Backup(backupCategories = listOf(BackupCategory(name = "Reading", uid = 7))),
        )

        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        assertTrue(decoded.backupManga.isEmpty())
        assertEquals("Reading", decoded.backupCategories.single().name)

        val empty = ProtoBuf.decodeFromByteArray(Backup.serializer(), ByteArray(0))
        assertTrue(empty.backupManga.isEmpty())
    }

    private fun feed(source: Long) = BackupFeed(source = source, global = true)

    // An omitted section means "unchanged"; a feed-only response used to be dropped as empty.
    @Test
    fun `an omitted section is never a change but a differing one is`() {
        val localFeed = feed(1)

        assertFalse(emptyList<BackupFeed>().changedFrom(listOf(localFeed)))
        assertFalse(listOf(localFeed).changedFrom(listOf(feed(1))))
        assertTrue(listOf(feed(2)).changedFrom(listOf(localFeed)))
        assertTrue(listOf(localFeed).changedFrom(emptyList()))
    }

    @Test
    fun `a response carrying only feeds still has something to restore`() {
        assertTrue(Backup().hasNothingToRestore())
        assertFalse(Backup(backupFeeds = listOf(feed(1))).hasNothingToRestore())
    }

    private fun category(id: Long, name: String, uid: Long, order: Long = 0) =
        Category(id = id, name = name, order = order, flags = 0, hidden = false, uid = uid)

    @Test
    fun `only categories the upload carried can be deleted remotely`() {
        val uploaded = listOf(
            BackupCategory(name = "Kept", uid = 1),
            BackupCategory(name = "Gone", uid = 2),
            BackupCategory(name = "Reading", uid = 9),
        )
        val remote = listOf(
            BackupCategory(name = "Kept", uid = 1),
            // same name, different uid: the other device's copy of it, so not a deletion
            BackupCategory(name = "Reading", uid = 7),
        )
        val local = listOf(
            category(id = 0, name = "", uid = 0),
            category(id = 3, name = "Kept", uid = 1),
            category(id = 4, name = "Gone", uid = 2),
            category(id = 5, name = "Reading", uid = 9),
            // created while the request was in flight: never uploaded, so never deleted
            category(id = 6, name = "Just made", uid = 3),
        )

        assertEquals(listOf(4L), categoriesDeletedRemotely(local, uploaded, remote).map { it.id })
    }
}
