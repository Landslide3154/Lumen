package eu.kanade.tachiyomi.data.library.komga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class KomgaSeriesUrlTest {

    @Test
    fun `parses plain url`() {
        parseKomgaSeriesUrl("https://komga.example.com/api/v1/series/ABC123") shouldBe
            KomgaSeriesRef("https://komga.example.com", "ABC123")
    }

    @Test
    fun `keeps sub path and port in base`() {
        parseKomgaSeriesUrl("http://192.168.1.9:25600/komga/api/v1/series/S1") shouldBe
            KomgaSeriesRef("http://192.168.1.9:25600/komga", "S1")
    }

    @Test
    fun `strips trailing slash`() {
        parseKomgaSeriesUrl("https://h/api/v1/series/S1/") shouldBe KomgaSeriesRef("https://h", "S1")
    }

    @Test
    fun `ignores query and fragment`() {
        parseKomgaSeriesUrl("https://h/api/v1/series/S1?page=1#x") shouldBe KomgaSeriesRef("https://h", "S1")
    }

    @Test
    fun `keeps percent encoding as is`() {
        parseKomgaSeriesUrl("https://h/api/v1/series/A%2FB") shouldBe KomgaSeriesRef("https://h", "A%2FB")
    }

    @Test
    fun `rejects readlist url`() {
        parseKomgaSeriesUrl("https://h/api/v1/readlists/R1") shouldBe null
    }

    @Test
    fun `rejects other source url`() {
        parseKomgaSeriesUrl("https://mangadex.org/title/abc") shouldBe null
    }

    @Test
    fun `rejects blank malformed and id-less`() {
        parseKomgaSeriesUrl("") shouldBe null
        parseKomgaSeriesUrl("not a url") shouldBe null
        parseKomgaSeriesUrl("https://h/api/v1/series/") shouldBe null
    }
}
