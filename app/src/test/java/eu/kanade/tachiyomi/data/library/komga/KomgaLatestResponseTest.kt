package eu.kanade.tachiyomi.data.library.komga

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class KomgaLatestResponseTest {

    private val client = KomgaChangeSetClient(OkHttpClient(), Json { ignoreUnknownKeys = true })

    private fun cfg(apiKey: String = "", user: String = "", pass: String = "") =
        KomgaSourceConfig("https://h", apiKey, user, pass)

    @Test
    fun `builds url with paging params`() {
        val request = client.buildRequest("https://h", cfg(apiKey = "k"), page = 2, pageSize = 500)

        request.url.toString() shouldBe "https://h/api/v1/series/latest?size=500&page=2"
    }

    @Test
    fun `uses api key header when present`() {
        val request = client.buildRequest("https://h", cfg(apiKey = "k", user = "u", pass = "p"), 0, 500)

        request.header("X-API-Key") shouldBe "k"
        request.header("Authorization") shouldBe null
    }

    @Test
    fun `falls back to basic auth`() {
        val request = client.buildRequest("https://h", cfg(user = "u", pass = "p"), 0, 500)

        request.header("X-API-Key") shouldBe null
        request.header("Authorization") shouldBe Credentials.basic("u", "p")
    }

    @Test
    fun `anonymous when no credentials`() {
        val request = client.buildRequest("https://h", cfg(), 0, 500)

        request.header("X-API-Key") shouldBe null
        request.header("Authorization") shouldBe null
    }

    @Test
    fun `parses spring page with unknown fields`() {
        val ok = client.parsePage(FULL_PAGE).shouldBeInstanceOf<KomgaPageResult.Ok>()

        ok.page.items shouldBe listOf(KomgaSeriesItem("S1", "2026-10-12T02:11:21Z"))
        ok.page.isLast shouldBe false
    }

    @Test
    fun `parses empty content and last flag`() {
        val ok = client.parsePage("""{"content":[],"last":true,"totalElements":0}""")
            .shouldBeInstanceOf<KomgaPageResult.Ok>()

        ok.page.items shouldBe emptyList()
        ok.page.isLast shouldBe true
    }

    @Test
    fun `malformed body yields failure`() {
        client.parsePage("<html>502 Bad Gateway</html>")
            .shouldBeInstanceOf<KomgaPageResult.Failed>()
    }

    private companion object {
        val FULL_PAGE = """
            {
              "content": [
                {
                  "id": "S1",
                  "libraryId": "L1",
                  "name": "Series One",
                  "created": "2020-01-01T00:00:00Z",
                  "lastModified": "2026-10-12T02:11:21Z",
                  "fileLastModified": "2026-10-11T00:00:00Z",
                  "booksCount": 3,
                  "booksReadCount": 1,
                  "booksUnreadCount": 2,
                  "booksInProgressCount": 0,
                  "metadata": { "status": "ONGOING", "title": "Series One", "genres": [], "tags": [] },
                  "booksMetadata": {
                    "authors": [], "summary": "", "summaryNumber": "",
                    "created": "2020-01-01T00:00:00Z", "lastModified": "2026-10-12T02:11:21Z"
                  },
                  "unknownFutureField": { "nested": [1, 2, 3] }
                }
              ],
              "pageable": { "pageNumber": 0, "pageSize": 500 },
              "last": false,
              "totalPages": 2,
              "totalElements": 1,
              "sort": { "sorted": true }
            }
        """.trimIndent()
    }
}
