package eu.kanade.tachiyomi.data.library.komga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.time.LocalDateTime

@Execution(ExecutionMode.CONCURRENT)
class KomgaServerOutcomeTest {

    private val watermark = LocalDateTime.parse("2026-10-12T02:00:00")

    @Test
    fun `complete change set maps to ok`() {
        val changeSet = KomgaChangeSet(
            changedIds = setOf("A"),
            maxSeen = "2026-10-12T03:00:00Z",
            complete = true,
            empty = false,
            pages = 2,
        )

        queryOutcome(changeSet, watermark).outcome shouldBe
            KomgaServerOutcome.Ok(
                setOf("A"),
                "2026-10-12T03:00:00Z",
                empty = false,
                times = mapOf("A" to "2026-10-12T03:00:00Z"),
            )
    }

    @Test
    fun `incomplete change set maps to failure keeping the reason`() {
        val changeSet = KomgaChangeSet(
            changedIds = emptySet(),
            maxSeen = null,
            complete = false,
            empty = false,
            pages = 0,
            failureReason = "HTTP 401",
        )

        (queryOutcome(changeSet, watermark).outcome as KomgaServerOutcome.Failed).reason shouldBe "HTTP 401"
    }

    @Test
    fun `watermark ahead of komga keeps everything and still advances`() {
        val changeSet = KomgaChangeSet(
            changedIds = emptySet(),
            maxSeen = "2026-10-12T01:00:00Z",
            complete = true,
            empty = false,
            pages = 1,
        )

        // 规格 §6.4-7：判定异常后本次按「全部有变化」处理，并且要把水位线重置为新的最大值
        queryOutcome(changeSet, watermark).outcome shouldBe
            KomgaServerOutcome.Ok(emptySet(), "2026-10-12T01:00:00Z", empty = true)
    }

    @Test
    fun `no watermark means nothing is backwards`() {
        val changeSet = KomgaChangeSet(
            changedIds = setOf("A"),
            maxSeen = "2020-01-01T00:00:00Z",
            complete = true,
            empty = false,
            pages = 1,
        )

        queryOutcome(changeSet, null).outcome shouldBe
            KomgaServerOutcome.Ok(
                setOf("A"),
                "2020-01-01T00:00:00Z",
                empty = false,
                times = mapOf("A" to "2020-01-01T00:00:00Z"),
            )
    }

    @Test
    fun `fallback reason is null on a successful query`() {
        val changeSet = KomgaChangeSet(setOf("A"), "2026-10-12T03:00:00Z", complete = true, empty = false, pages = 1)

        queryOutcome(changeSet, watermark).fallbackReason shouldBe null
    }

    @Test
    fun `fallback reason keeps the failure reason`() {
        val changeSet = KomgaChangeSet(
            changedIds = emptySet(),
            maxSeen = null,
            complete = false,
            empty = false,
            pages = 0,
            failureReason = "timeout after 5000ms",
        )

        queryOutcome(changeSet, watermark).fallbackReason shouldBe "timeout after 5000ms"
    }

    @Test
    fun `fallback reason reports backwards watermark`() {
        val changeSet = KomgaChangeSet(
            changedIds = emptySet(),
            maxSeen = "2026-10-12T01:00:00Z",
            complete = true,
            empty = false,
            pages = 1,
        )

        queryOutcome(changeSet, watermark).fallbackReason shouldBe "watermark ahead of Komga"
    }

    @Test
    fun `fallback reason defaults for an incomplete set without a reason`() {
        val changeSet = KomgaChangeSet(emptySet(), null, complete = false, empty = false, pages = 10)

        queryOutcome(changeSet, watermark).fallbackReason shouldBe "incomplete result (page/time limit)"
    }
}
