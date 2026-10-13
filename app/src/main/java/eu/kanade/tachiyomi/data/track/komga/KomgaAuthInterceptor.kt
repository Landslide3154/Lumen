package eu.kanade.tachiyomi.data.track.komga

import android.app.Application
import eu.kanade.tachiyomi.data.library.komga.KomgaSourceConfig
import eu.kanade.tachiyomi.data.library.komga.readKomgaSourceConfig
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK -->
/**
 * Komga 追踪器的请求不带任何凭证（见 [KomgaApi]），遇到需要登录的 Komga 就是 401，而同一台服务器
 * 的图源却是好的：账号存在图源自己的偏好里。这里按请求地址找到对应的 Komga 图源，把它的认证头带上。
 *
 * 地址取最长匹配，多个 Komga 图源（例如 `https://host` 与 `https://host/komga`）时选最具体的那个。
 */
internal class KomgaAuthInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val header = komgaAuthHeader(request.url.toString(), komgaConfigs()) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(header.first, header.second).build())
    }

    private fun komgaConfigs(): List<KomgaSourceConfig> {
        // the application is registered under its own type, not as a plain Context
        val context: Application = Injekt.get()
        val sourceManager: SourceManager = Injekt.get()
        return sourceManager.getAll()
            .filter { it.javaClass.name == Komga.SOURCE_CLASS }
            .mapNotNull { readKomgaSourceConfig(context, it.id) }
    }
}

/** The header that authenticates [url], or null when no Komga source covers it. */
internal fun komgaAuthHeader(url: String, configs: List<KomgaSourceConfig>): Pair<String, String>? {
    val config = configs
        .filter { it.address.isNotEmpty() && url.startsWith(it.address) }
        .maxByOrNull { it.address.length }
        ?: return null

    return when {
        // the extension sends the API key as a header and otherwise logs in with basic auth
        config.hasApiKey -> "X-API-Key" to config.apiKey
        config.hasBasicAuth -> "Authorization" to Credentials.basic(config.username, config.password)
        else -> null
    }
}
// KMK <--
