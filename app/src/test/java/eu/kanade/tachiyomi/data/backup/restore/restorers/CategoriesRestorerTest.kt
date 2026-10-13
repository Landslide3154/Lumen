package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

class CategoriesRestorerTest {

    private val reading = category(id = 3, name = "Reading", uid = 7, order = 0)

    @Test
    fun `a sync merges a renamed category instead of inserting a second row`() {
        val renamed = backupCategory(name = "Reading list", uid = 7)

        planCategoryRestore(listOf(renamed), listOf(reading), isSync = true) shouldBe listOf(
            CategoryRestore.Merge(reading, category(id = 3, name = "Reading list", uid = 7, order = 0)),
        )
    }

    @Test
    fun `a sync applies the position the server holds`() {
        val moved = backupCategory(name = "Reading", uid = 7, order = 4)

        planCategoryRestore(listOf(moved), listOf(reading), isSync = true) shouldBe listOf(
            CategoryRestore.Merge(reading, category(id = 3, name = "Reading", uid = 7, order = 4)),
        )
    }

    @Test
    fun `a sync inserts a new category at the position the server holds`() {
        val added = backupCategory(name = "Later", uid = 8, order = 5)

        planCategoryRestore(listOf(added), listOf(reading), isSync = true) shouldBe listOf(
            CategoryRestore.Insert(added, order = 5),
        )
    }

    @Test
    fun `an unchanged category is left alone`() {
        val same = backupCategory(name = "Reading", uid = 7)

        planCategoryRestore(listOf(same), listOf(reading), isSync = true) shouldBe listOf(
            CategoryRestore.Keep(reading),
        )
    }

    @Test
    fun `a sync falls back to the name when the uid is unknown`() {
        val otherDeviceCopy = backupCategory(name = "Reading", uid = 7, order = 0, version = 6)
        val local = category(id = 3, name = "Reading", uid = 9, order = 2)

        planCategoryRestore(listOf(otherDeviceCopy), listOf(local), isSync = true) shouldBe listOf(
            CategoryRestore.Merge(local, category(id = 3, name = "Reading", uid = 7, order = 0, version = 6)),
        )
    }

    @Test
    fun `an import appends new categories and keeps the ones that are already there`() {
        val known = backupCategory(name = "Reading", uid = 0)
        val added = backupCategory(name = "New", uid = 0)
        val local = category(id = 3, name = "Reading", uid = 7, order = 4)

        planCategoryRestore(listOf(known, added), listOf(local), isSync = false) shouldBe listOf(
            CategoryRestore.Keep(local),
            CategoryRestore.Insert(added, order = 5),
        )
    }

    @Test
    fun `the system category is never matched`() {
        val system = Category(id = 0, name = "", order = -1, flags = 0, hidden = false, version = 0, uid = 0)
        val added = backupCategory(name = "New", uid = 0)

        planCategoryRestore(listOf(added), listOf(system), isSync = true) shouldBe listOf(
            CategoryRestore.Insert(added, order = 0),
        )
    }

    private fun category(id: Long, name: String, uid: Long, order: Long, version: Long = 1) =
        Category(id = id, name = name, order = order, flags = 0, hidden = false, version = version, uid = uid)

    private fun backupCategory(name: String, uid: Long, order: Long = 0, version: Long = 1) =
        BackupCategory(name = name, order = order, uid = uid, version = version)
}
