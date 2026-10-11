package eu.kanade.tachiyomi.data.library.komga

import kotlinx.serialization.json.Json
import java.time.LocalDateTime

// KMK -->
/**
 * 增量更新的「水位线」：上次成功更新时在 Komga 上看到的最大 lastModified。
 *
 * Komga 返回的 `lastModified` 形如 `2026-10-12T02:11:21Z`，其中结尾的 Z 是被转义的字面字符，
 * 不代表 UTC。因此这里**只做同字段的前后值比较**，全程不使用手机时钟与手机时区。
 */
object KomgaWatermarks {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseLastModified(raw: String?): LocalDateTime? {
        val value = raw?.trim()?.removeSuffix("Z")?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDateTime.parse(value)
        } catch (_: Exception) {
            null
        }
    }

    /** 任一侧无法解析时返回 true（宁可多拉，不可漏）。 */
    fun isChanged(lastModified: String?, watermark: LocalDateTime?): Boolean {
        if (watermark == null) return true
        val value = parseLastModified(lastModified) ?: return true
        return !value.isBefore(watermark)
    }

    /** Komga 上的时间倒流（例如从备份恢复）时返回 true。 */
    fun isBackwards(maxSeen: String?, watermark: LocalDateTime?): Boolean {
        if (watermark == null) return false
        val value = parseLastModified(maxSeen) ?: return false
        return value.isBefore(watermark)
    }

    fun encode(watermarks: Map<String, String>): String =
        try {
            json.encodeToString(watermarks)
        } catch (_: Exception) {
            ""
        }

    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
// KMK <--
