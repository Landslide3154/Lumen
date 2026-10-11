package eu.kanade.tachiyomi.data.library.komga

// KMK -->
/**
 * Komga 作品网址的解析结果。[baseUrl] 已去掉结尾斜杠，[seriesId] 保持原样（不做 URL 解码）。
 */
data class KomgaSeriesRef(val baseUrl: String, val seriesId: String)

private val KOMGA_SERIES_URL_REGEX =
    Regex("""^(?<base>https?://.+?)/api/v1/series/(?<id>[^/?#]+)/?(?:[?#].*)?$""")

/**
 * 把 `manga.url` 解析成 Komga 作品引用。
 *
 * 只认「网址形状」，不依赖 sourceId，也不关心用户装的是哪个 Komga 扩展；
 * Komga 的「书单」（/api/v1/readlists/...）与其它来源一律返回 null。
 */
fun parseKomgaSeriesUrl(url: String): KomgaSeriesRef? {
    val match = KOMGA_SERIES_URL_REGEX.matchEntire(url.trim()) ?: return null
    val baseUrl = match.groups["base"]?.value?.removeSuffix("/") ?: return null
    val seriesId = match.groups["id"]?.value ?: return null
    if (baseUrl.isEmpty() || seriesId.isEmpty()) return null
    return KomgaSeriesRef(baseUrl, seriesId)
}
// KMK <--
