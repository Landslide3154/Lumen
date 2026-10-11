package eu.kanade.tachiyomi.data.library.komga

import eu.kanade.tachiyomi.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext

// KMK -->
/**
 * 只做一件事：按页取 `/api/v1/series/latest`，并把结果解析成 [KomgaPage]。
 *
 * 凭证来自 Komga 扩展自己的偏好（见 [KomgaSourceConfig]），与 App 内的 Komga 追踪器无关。
 */
class KomgaChangeSetClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    fun buildRequest(baseUrl: String, config: KomgaSourceConfig, page: Int, pageSize: Int): Request {
        val builder = Request.Builder()
            .url("${baseUrl.removeSuffix("/")}$LATEST_PATH?size=$pageSize&page=$page")
            .header("User-Agent", "Lumen v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .header("Accept", "application/json")

        when {
            config.hasApiKey -> builder.header("X-API-Key", config.apiKey)
            config.hasBasicAuth -> builder.header(
                "Authorization",
                Credentials.basic(config.username, config.password),
            )
        }

        return builder.build()
    }

    fun parsePage(body: String): KomgaPageResult = try {
        val dto = json.decodeFromString<KomgaLatestPageDto>(body)
        KomgaPageResult.Ok(
            KomgaPage(
                items = dto.content.map { KomgaSeriesItem(it.id, it.lastModified) },
                isLast = dto.last,
            ),
        )
    } catch (e: Exception) {
        KomgaPageResult.Failed("parse error: ${e.message}")
    }

    suspend fun fetchPage(
        baseUrl: String,
        config: KomgaSourceConfig,
        page: Int,
        pageSize: Int = PAGE_SIZE,
    ): KomgaPageResult = withIOContext {
        try {
            client.newCall(buildRequest(baseUrl, config, page, pageSize)).execute().use { response ->
                if (!response.isSuccessful) {
                    KomgaPageResult.Failed("HTTP ${response.code}")
                } else {
                    parsePage(response.body.string())
                }
            }
        } catch (e: Exception) {
            KomgaPageResult.Failed(e.message ?: e::class.simpleName ?: "unknown error")
        }
    }

    companion object {
        const val PAGE_SIZE = 500
        private const val LATEST_PATH = "/api/v1/series/latest"
    }
}

@Serializable
private data class KomgaLatestPageDto(
    val content: List<KomgaLatestSeriesDto> = emptyList(),
    val last: Boolean = false,
)

@Serializable
private data class KomgaLatestSeriesDto(
    val id: String,
    val lastModified: String? = null,
)
// KMK <--
