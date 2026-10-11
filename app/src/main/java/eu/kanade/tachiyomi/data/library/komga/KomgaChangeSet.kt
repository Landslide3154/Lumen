package eu.kanade.tachiyomi.data.library.komga

import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

// KMK -->
/** Komga 分页响应里我们唯一需要的两项。 */
data class KomgaSeriesItem(val id: String, val lastModified: String?)

data class KomgaPage(val items: List<KomgaSeriesItem>, val isLast: Boolean)

sealed interface KomgaPageResult {
    data class Ok(val page: KomgaPage) : KomgaPageResult

    data class Failed(val reason: String) : KomgaPageResult
}

/**
 * 一次「哪些系列有变化」的查询结果。
 *
 * @param changedIds `lastModified >= 水位线` 的 seriesId
 * @param maxSeen 本次看到的最大 lastModified（原样字符串），作为成功后的新水位线
 * @param complete false 表示命中页数/时间上限或请求失败，调用方应按策略回退
 * @param empty Komga 上一本书都没有 → 调用方视为「全部有变化」
 */
data class KomgaChangeSet(
    val changedIds: Set<String>,
    val maxSeen: String?,
    val complete: Boolean,
    val empty: Boolean,
)

/**
 * 逐页收集变更集合。按 lastModified 倒序，遇到比水位线更旧的条目即停止翻页。
 *
 * 传入 [fetchPage] 而不是直接发请求，是为了让分页与停止规则可以脱离网络单独测试。
 */
suspend fun collectChangedSeries(
    watermark: LocalDateTime?,
    fetchPage: suspend (page: Int) -> KomgaPageResult,
    maxPages: Int = 10,
    timeoutMillis: Long = 5_000,
): KomgaChangeSet {
    val collected: KomgaChangeSet? = withTimeoutOrNull(timeoutMillis) {
        val changedIds = mutableSetOf<String>()
        var maxSeen: String? = null
        var maxSeenParsed: LocalDateTime? = null
        var empty = false
        var complete = false
        var page = 0

        while (page < maxPages) {
            when (val result = fetchPage(page)) {
                is KomgaPageResult.Failed -> return@withTimeoutOrNull null

                is KomgaPageResult.Ok -> {
                    val items = result.page.items
                    if (page == 0) {
                        empty = items.isEmpty()
                        if (empty) {
                            complete = true
                            return@withTimeoutOrNull KomgaChangeSet(changedIds, null, complete, empty)
                        }
                    }

                    var stopped = false
                    for (item in items) {
                        val parsed = KomgaWatermarks.parseLastModified(item.lastModified)
                        if (parsed != null && (maxSeenParsed == null || parsed.isAfter(maxSeenParsed))) {
                            maxSeenParsed = parsed
                            maxSeen = item.lastModified
                        }
                        if (KomgaWatermarks.isChanged(item.lastModified, watermark)) {
                            changedIds += item.id
                        } else {
                            stopped = true
                            break
                        }
                    }

                    page++
                    if (stopped || result.page.isLast) {
                        complete = true
                        break
                    }
                }
            }
        }

        KomgaChangeSet(changedIds, maxSeen, complete, empty)
    }

    return collected ?: KomgaChangeSet(emptySet(), null, complete = false, empty = false)
}
// KMK <--
