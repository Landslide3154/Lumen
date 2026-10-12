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
 * @param pages 成功取到的页数（用于日志诊断）
 * @param failureReason 失败原因（HTTP 码 / 异常消息 / timeout），不含凭据
 */
data class KomgaChangeSet(
    val changedIds: Set<String>,
    val maxSeen: String?,
    val complete: Boolean,
    val empty: Boolean,
    val pages: Int = 0,
    val failureReason: String? = null,
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
    timeoutMillis: Long = 15_000,
): KomgaChangeSet {
    var pagesFetched = 0

    val collected: KomgaChangeSet? = withTimeoutOrNull(timeoutMillis) {
        val changedIds = mutableSetOf<String>()
        var maxSeen: String? = null
        var maxSeenParsed: LocalDateTime? = null
        var empty = false
        var complete = false
        var page = 0

        while (page < maxPages) {
            when (val result = fetchPage(page)) {
                is KomgaPageResult.Failed -> return@withTimeoutOrNull KomgaChangeSet(
                    changedIds = emptySet(),
                    maxSeen = null,
                    complete = false,
                    empty = false,
                    pages = pagesFetched,
                    failureReason = result.reason,
                )

                is KomgaPageResult.Ok -> {
                    pagesFetched++
                    val items = result.page.items
                    if (page == 0) {
                        empty = items.isEmpty()
                        if (empty) {
                            complete = true
                            return@withTimeoutOrNull KomgaChangeSet(
                                changedIds = changedIds,
                                maxSeen = null,
                                complete = complete,
                                empty = empty,
                                pages = pagesFetched,
                            )
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

        KomgaChangeSet(changedIds, maxSeen, complete, empty, pages = pagesFetched)
    }

    return collected ?: KomgaChangeSet(
        changedIds = emptySet(),
        maxSeen = null,
        complete = false,
        empty = false,
        pages = pagesFetched,
        failureReason = "timeout after ${timeoutMillis}ms",
    )
}

/**
 * 把一次查询结果换算成服务器结局。
 *
 * 时间倒流（Komga 从备份恢复、最新系列被删）按规格 §6.4-7 处理：本次**全部**按「有变化」
 * （`empty = true` 让 decider 保留该服务器的所有条目），**并且**用本次的 maxSeen 推进水位线，
 * 否则水位线会永远卡在未来、SKIP 策略下所有 Komga 条目被永久跳过。
 */
fun toServerOutcome(changeSet: KomgaChangeSet, watermark: LocalDateTime?): KomgaServerOutcome = when {
    KomgaWatermarks.isBackwards(changeSet.maxSeen, watermark) ->
        KomgaServerOutcome.Ok(changedIds = emptySet(), maxSeen = changeSet.maxSeen, empty = true)

    changeSet.complete ->
        KomgaServerOutcome.Ok(changeSet.changedIds, changeSet.maxSeen, changeSet.empty)

    else ->
        KomgaServerOutcome.Failed(changeSet.failureReason ?: "incomplete result (page/time limit)")
}

/**
 * 这次查询是否需要回退全量，以及原因；正常时返回 null。
 *
 * 回退时更新页会把这句原因显示出来——否则用户只看到「这次全量跑了一遍」，
 * 完全不知道是网络超时、401 还是翻页被截断（日志默认被日志级别挡掉）。
 */
fun fallbackReason(changeSet: KomgaChangeSet, watermark: LocalDateTime?): String? = when {
    KomgaWatermarks.isBackwards(changeSet.maxSeen, watermark) -> "watermark ahead of Komga"

    changeSet.complete -> null

    else -> changeSet.failureReason ?: "incomplete result (page/time limit)"
}
// KMK <--
