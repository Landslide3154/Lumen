package eu.kanade.tachiyomi.data.library.komga

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.time.LocalDateTime

@Execution(ExecutionMode.CONCURRENT)
class KomgaChangeSetTest {

    private fun item(id: String, time: String?) =
        KomgaSeriesItem(id, time?.let { "2026-10-12T$it:00Z" })

    private fun wm(time: String) = LocalDateTime.parse("2026-10-12T$time:00")

    @Test
    fun `collects items at or after watermark and stops at older one`() = runTest {
        val pages = listOf(
            KomgaPage(listOf(item("A", "03:00"), item("B", "02:00")), isLast = false),
            KomgaPage(listOf(item("C", "01:00"), item("D", "00:00")), isLast = false),
            KomgaPage(listOf(item("E", "00:00")), isLast = true),
        )
        val requested = mutableListOf<Int>()

        val result = collectChangedSeries(
            watermark = wm("02:00"),
            fetchPage = { page ->
                requested += page
                KomgaPageResult.Ok(pages[page])
            },
        )

        result.changedIds shouldBe setOf("A", "B")
        requested shouldBe listOf(0, 1)
        result.complete shouldBe true
        result.pages shouldBe 2
        result.failureReason shouldBe null
        result.maxSeen shouldBe "2026-10-12T03:00:00Z"
        result.empty shouldBe false
    }

    @Test
    fun `null watermark collects everything until last page`() = runTest {
        val pages = listOf(
            KomgaPage(listOf(item("A", "03:00"), item("B", "02:00")), isLast = false),
            KomgaPage(listOf(item("C", "01:00")), isLast = true),
        )
        val requested = mutableListOf<Int>()

        val result = collectChangedSeries(
            watermark = null,
            fetchPage = { page ->
                requested += page
                KomgaPageResult.Ok(pages[page])
            },
        )

        result.changedIds shouldBe setOf("A", "B", "C")
        requested shouldBe listOf(0, 1)
        result.complete shouldBe true
        result.maxSeen shouldBe "2026-10-12T03:00:00Z"
    }

    @Test
    fun `hitting max pages marks incomplete`() = runTest {
        val page = KomgaPage(listOf(item("A", "03:00")), isLast = false)
        val requested = mutableListOf<Int>()

        val result = collectChangedSeries(
            watermark = null,
            fetchPage = { p ->
                requested += p
                KomgaPageResult.Ok(page)
            },
            maxPages = 2,
        )

        result.complete shouldBe false
        requested shouldBe listOf(0, 1)
        result.pages shouldBe 2
    }

    @Test
    fun `fetch failure marks incomplete and returns nothing`() = runTest {
        val result = collectChangedSeries(
            watermark = null,
            fetchPage = { KomgaPageResult.Failed("timeout") },
        )

        result.complete shouldBe false
        result.changedIds shouldBe emptySet()
        result.maxSeen shouldBe null
        result.pages shouldBe 0
        result.failureReason shouldBe "timeout"
    }

    @Test
    fun `timeout marks incomplete`() = runTest {
        val result = collectChangedSeries(
            watermark = null,
            fetchPage = {
                delay(10_000)
                KomgaPageResult.Ok(KomgaPage(emptyList(), isLast = true))
            },
            timeoutMillis = 5_000,
        )

        result.complete shouldBe false
        result.changedIds shouldBe emptySet()
    }

    @Test
    fun `empty first page marks empty`() = runTest {
        val requested = mutableListOf<Int>()

        val result = collectChangedSeries(
            watermark = null,
            fetchPage = { p ->
                requested += p
                KomgaPageResult.Ok(KomgaPage(emptyList(), isLast = false))
            },
        )

        result.empty shouldBe true
        result.changedIds shouldBe emptySet()
        result.complete shouldBe true
        result.maxSeen shouldBe null
        requested shouldBe listOf(0)
    }

    @Test
    fun `unparsable lastModified is kept as changed`() = runTest {
        val result = collectChangedSeries(
            watermark = wm("02:00"),
            fetchPage = { KomgaPageResult.Ok(KomgaPage(listOf(item("Z", null)), isLast = true)) },
        )

        result.changedIds shouldBe setOf("Z")
        result.maxSeen shouldBe null
    }
}
