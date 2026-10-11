package eu.kanade.tachiyomi.data.library.komga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class KomgaSourceConfigTest {

    @Test
    fun `maps the four extension keys`() {
        val cfg = KomgaSourceConfig.from(
            mapOf(
                "Address" to "https://komga.example.com",
                "API key" to "secret",
                "Username" to "u",
                "Password" to "p",
            ),
        )
        cfg shouldBe KomgaSourceConfig("https://komga.example.com", "secret", "u", "p")
    }

    @Test
    fun `strips trailing slash from address`() {
        KomgaSourceConfig.from(mapOf("Address" to "https://h/"))?.address shouldBe "https://h"
    }

    @Test
    fun `returns null when address missing or blank`() {
        KomgaSourceConfig.from(emptyMap()) shouldBe null
        KomgaSourceConfig.from(mapOf("API key" to "k")) shouldBe null
        KomgaSourceConfig.from(mapOf("Address" to "   ")) shouldBe null
    }

    @Test
    fun `treats missing optional values as empty strings`() {
        val cfg = KomgaSourceConfig.from(mapOf("Address" to "https://h"))!!
        cfg.apiKey shouldBe ""
        cfg.username shouldBe ""
        cfg.password shouldBe ""
    }

    @Test
    fun `api key wins over basic auth`() {
        val withKey = KomgaSourceConfig.from(
            mapOf("Address" to "https://h", "API key" to "k", "Username" to "u"),
        )!!
        withKey.hasApiKey shouldBe true
        withKey.hasBasicAuth shouldBe false

        val basic = KomgaSourceConfig.from(
            mapOf("Address" to "https://h", "Username" to "u", "Password" to "p"),
        )!!
        basic.hasApiKey shouldBe false
        basic.hasBasicAuth shouldBe true
    }

    @Test
    fun `anonymous when only address is set`() {
        val cfg = KomgaSourceConfig.from(mapOf("Address" to "https://h"))!!
        cfg.hasApiKey shouldBe false
        cfg.hasBasicAuth shouldBe false
    }
}
