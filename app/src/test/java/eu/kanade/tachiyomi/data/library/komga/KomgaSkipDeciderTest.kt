package eu.kanade.tachiyomi.data.library.komga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.library.service.LibraryPreferences.KomgaUnavailablePolicy

@Execution(ExecutionMode.CONCURRENT)
class KomgaSkipDeciderTest {

    private fun entry(
        mangaId: Long,
        sourceId: Long = 1L,
        baseUrl: String? = "https://h",
        seriesId: String? = "S$mangaId",
    ) = KomgaEntryRef(mangaId, sourceId, baseUrl, seriesId)

    private fun ok(
        changedIds: Set<String> = emptySet(),
        maxSeen: String? = null,
        empty: Boolean = false,
    ) = KomgaServerOutcome.Ok(changedIds, maxSeen, empty)

    @Test
    fun `non komga entry is always kept`() {
        val decision = decide(
            entries = listOf(entry(1, baseUrl = null, seriesId = null)),
            outcomes = emptyMap(),
            policy = KomgaUnavailablePolicy.FALLBACK_FULL,
        )

        decision.keptIds shouldBe setOf(1L)
        decision.skippedUnchangedIds shouldBe emptySet()
        decision.skippedUnavailableIds shouldBe emptySet()
    }

    @Test
    fun `missing config keeps entry`() {
        val decision = decide(
            entries = listOf(entry(1)),
            outcomes = emptyMap(),
            policy = KomgaUnavailablePolicy.SKIP,
        )

        decision.keptIds shouldBe setOf(1L)
        decision.skippedUnavailableIds shouldBe emptySet()
    }

    @Test
    fun `failure falls back to full by default`() {
        val decision = decide(
            entries = listOf(entry(1), entry(2)),
            outcomes = mapOf("https://h" to KomgaServerOutcome.Failed("timeout")),
            policy = KomgaUnavailablePolicy.FALLBACK_FULL,
        )

        decision.keptIds shouldBe setOf(1L, 2L)
        decision.skippedUnavailableIds shouldBe emptySet()
    }

    @Test
    fun `failure skips when policy is skip`() {
        val decision = decide(
            entries = listOf(entry(1), entry(2)),
            outcomes = mapOf("https://h" to KomgaServerOutcome.Failed("timeout")),
            policy = KomgaUnavailablePolicy.SKIP,
        )

        decision.keptIds shouldBe emptySet()
        decision.skippedUnavailableIds shouldBe setOf(1L, 2L)
        decision.skippedUnchangedIds shouldBe emptySet()
    }

    @Test
    fun `empty server keeps everything`() {
        val decision = decide(
            entries = listOf(entry(1), entry(2)),
            outcomes = mapOf("https://h" to ok(empty = true)),
            policy = KomgaUnavailablePolicy.FALLBACK_FULL,
        )

        decision.keptIds shouldBe setOf(1L, 2L)
        decision.skippedUnchangedIds shouldBe emptySet()
    }

    @Test
    fun `unchanged entry is skipped and changed entry is kept`() {
        val decision = decide(
            entries = listOf(entry(1), entry(2), entry(3)),
            outcomes = mapOf("https://h" to ok(changedIds = setOf("S2"))),
            policy = KomgaUnavailablePolicy.FALLBACK_FULL,
        )

        decision.keptIds shouldBe setOf(2L)
        decision.skippedUnchangedIds shouldBe setOf(1L, 3L)
    }

    @Test
    fun `same address used by two sources is decided once`() {
        val decision = decide(
            entries = listOf(
                entry(1, sourceId = 10L, seriesId = "S1"),
                entry(2, sourceId = 20L, seriesId = "S2"),
            ),
            outcomes = mapOf("https://h" to ok(changedIds = setOf("S2"))),
            policy = KomgaUnavailablePolicy.FALLBACK_FULL,
        )

        decision.keptIds shouldBe setOf(2L)
        decision.skippedUnchangedIds shouldBe setOf(1L)
    }

    @Test
    fun `stale watermark for a removed address does not break others`() {
        val decision = decide(
            entries = listOf(
                entry(1, baseUrl = "https://old", seriesId = "S1"),
                entry(2, baseUrl = "https://h", seriesId = "S2"),
            ),
            outcomes = mapOf("https://h" to ok(changedIds = setOf("S2"), maxSeen = "2026-10-12T03:00:00Z")),
            policy = KomgaUnavailablePolicy.FALLBACK_FULL,
        )

        decision.keptIds shouldBe setOf(1L, 2L)
        decision.newWatermarks shouldBe mapOf("https://h" to "2026-10-12T03:00:00Z")
    }

    @Test
    fun `new watermarks only from successful servers with maxSeen`() {
        val decision = decide(
            entries = listOf(entry(1)),
            outcomes = mapOf(
                "https://h" to ok(changedIds = setOf("S1"), maxSeen = null),
                "https://dead" to KomgaServerOutcome.Failed("401"),
            ),
            policy = KomgaUnavailablePolicy.FALLBACK_FULL,
        )

        decision.newWatermarks shouldBe emptyMap()
    }

    @Test
    fun `entry whose last update failed is kept next run`() {
        val decision = decide(
            entries = listOf(entry(1), entry(2)),
            outcomes = mapOf("https://h" to ok(changedIds = emptySet())),
            policy = KomgaUnavailablePolicy.FALLBACK_FULL,
            retryIds = setOf(1L),
        )

        decision.keptIds shouldBe setOf(1L)
        decision.skippedUnchangedIds shouldBe setOf(2L)
    }

    @Test
    fun `implausible series id is not matchable`() {
        isMatchableKomgaSeriesId("4f7b1c2e-1111-2222-3333-444455556666") shouldBe true
        isMatchableKomgaSeriesId("A%2FB") shouldBe false
        isMatchableKomgaSeriesId("has space") shouldBe false
        isMatchableKomgaSeriesId("") shouldBe false
        isMatchableKomgaSeriesId("x".repeat(100)) shouldBe false
    }

    @Test
    fun `retry ids parse and encode`() {
        parseRetryIds("12, 34,,x") shouldBe setOf(12L, 34L)
        parseRetryIds("") shouldBe emptySet()
        encodeRetryIds(setOf(2L, 1L)) shouldBe "1,2"
    }
}
