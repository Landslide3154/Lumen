package eu.kanade.tachiyomi.data.library.komga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.time.LocalDateTime

@Execution(ExecutionMode.CONCURRENT)
class KomgaWatermarkTest {

    private val watermark = LocalDateTime.of(2026, 10, 12, 2, 0, 0)

    @Test
    fun `parses with and without fractional seconds`() {
        KomgaWatermarks.parseLastModified("2026-10-12T02:11:21Z") shouldBe
            LocalDateTime.of(2026, 10, 12, 2, 11, 21)
        KomgaWatermarks.parseLastModified("2026-10-12T02:11:21.500Z") shouldBe
            LocalDateTime.of(2026, 10, 12, 2, 11, 21, 500_000_000)
    }

    @Test
    fun `returns null for null blank or garbage`() {
        KomgaWatermarks.parseLastModified(null) shouldBe null
        KomgaWatermarks.parseLastModified("") shouldBe null
        KomgaWatermarks.parseLastModified("yesterday") shouldBe null
    }

    @Test
    fun `changed when equal greater or watermark missing`() {
        KomgaWatermarks.isChanged("2026-10-12T02:00:00Z", watermark) shouldBe true
        KomgaWatermarks.isChanged("2026-10-12T03:00:00Z", watermark) shouldBe true
        KomgaWatermarks.isChanged("2026-10-12T01:00:00Z", watermark) shouldBe false
        KomgaWatermarks.isChanged("2026-10-12T01:00:00Z", null) shouldBe true
    }

    @Test
    fun `unparsable lastModified counts as changed`() {
        KomgaWatermarks.isChanged(null, watermark) shouldBe true
        KomgaWatermarks.isChanged("not a date", watermark) shouldBe true
    }

    @Test
    fun `backwards only when strictly older`() {
        KomgaWatermarks.isBackwards("2026-10-12T01:00:00Z", watermark) shouldBe true
        KomgaWatermarks.isBackwards("2026-10-12T02:00:00Z", watermark) shouldBe false
        KomgaWatermarks.isBackwards("2026-10-12T03:00:00Z", watermark) shouldBe false
        KomgaWatermarks.isBackwards(null, watermark) shouldBe false
        KomgaWatermarks.isBackwards("2026-10-12T01:00:00Z", null) shouldBe false
    }

    @Test
    fun `encode decode round trip`() {
        val map = mapOf(
            "https://a" to "2026-10-12T02:11:21Z",
            "http://192.168.1.9:25600/komga" to "2026-10-11T22:00:00Z",
        )
        KomgaWatermarks.decode(KomgaWatermarks.encode(map)) shouldBe map
    }

    @Test
    fun `decode tolerates null empty and garbage`() {
        KomgaWatermarks.decode(null) shouldBe emptyMap()
        KomgaWatermarks.decode("") shouldBe emptyMap()
        KomgaWatermarks.decode("{ not json") shouldBe emptyMap()
    }
}
