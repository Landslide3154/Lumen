package eu.kanade.tachiyomi.data.track.komga

import eu.kanade.tachiyomi.data.library.komga.KomgaSourceConfig
import okhttp3.Credentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KomgaAuthTest {

    private val seriesUrl = "https://komga.example.com:3154/api/v1/series/0QX7KTQBY0ES7"

    private fun config(
        address: String,
        apiKey: String = "",
        username: String = "",
        password: String = "",
    ) = KomgaSourceConfig(address = address, apiKey = apiKey, username = username, password = password)

    @Test
    fun `uses the api key the source stored`() {
        val header = komgaAuthHeader(seriesUrl, listOf(config("https://komga.example.com:3154", apiKey = "secret")))

        assertEquals("X-API-Key" to "secret", header)
    }

    @Test
    fun `falls back to basic auth when there is no api key`() {
        val header = komgaAuthHeader(
            seriesUrl,
            listOf(config("https://komga.example.com:3154", username = "me@example.com", password = "pw")),
        )

        assertEquals("Authorization" to Credentials.basic("me@example.com", "pw"), header)
    }

    @Test
    fun `a source on another host does not answer for this url`() {
        val header = komgaAuthHeader(seriesUrl, listOf(config("https://other.example.com", apiKey = "secret")))

        assertNull(header)
    }

    @Test
    fun `the most specific address wins`() {
        val header = komgaAuthHeader(
            "https://komga.example.com/komga/api/v1/series/1",
            listOf(
                config("https://komga.example.com", apiKey = "outer"),
                config("https://komga.example.com/komga", apiKey = "inner"),
            ),
        )

        assertEquals("X-API-Key" to "inner", header)
    }

    @Test
    fun `a source without credentials leaves the request unauthenticated`() {
        assertNull(komgaAuthHeader(seriesUrl, listOf(config("https://komga.example.com:3154"))))
        assertNull(komgaAuthHeader(seriesUrl, emptyList()))
    }
}
