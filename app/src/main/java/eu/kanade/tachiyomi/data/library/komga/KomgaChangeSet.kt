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
 * @param changed 有变化的系列：seriesId → lastModified（原样字符串）。
 *   存时间而不是只存 id，是因为水位线只能推进到**本轮真正处理过**的那些条目，
 *   否则被分类设置/更新限制排除在外的系列会被水位线越过，从此静默漏更新。
 * @param maxSeen 本次看到的最大 lastModified（原样字符串）
 * @param complete false 表示命中页数/时间上限或请求失败，调用方应按策略回退
 * @param empty Komga 上一本书都没有（或时间倒流）→ 调用方视为「全部有变化」
 * @param pages 成功取到的页数（用于日志诊断）
 * @param failureReason 失败原因（HTTP 码 / 异常消息 / timeout），不含凭据
 */
data class KomgaChangeSet(
    val changed: Map<String, String?>,
    val maxSeen: String?,
    val complete: Boolean,
    val empty: Boolean,
    val pages: Int = 0,
    val failureReason: String? = null,
) {
    /** 只关心「哪些系列有变化」时用。 */
    val changedIds: Set<String> get() = changed.keys

    /** 便利构造：只给「哪些系列有变化」，时间统一按 [maxSeen] 兜底。 */
    constructor(
        changedIds: Set<String>,
        maxSeen: String?,
        complete: Boolean,
        empty: Boolean,
        pages: Int = 0,
        failureReason: String? = null,
    ) : this(changedIds.associateWith { maxSeen }, maxSeen, complete, empty, pages, failureReason)
}

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
        val changed = mutableMapOf<String, String?>()
        var maxSeen: String? = null
        var maxSeenParsed: LocalDateTime? = null
        var empty = false
        var complete = false
        var page = 0

        while (page < maxPages) {
            when (val result = fetchPage(page)) {
                is KomgaPageResult.Failed -> return@withTimeoutOrNull KomgaChangeSet(
                    changed = emptyMap(),
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
                                changed = changed,
                                maxSeen = null,
                                complete = complete,
                                empty = empty,
                                pages = pagesFetched,
                            )
                        }

                        // 首次（还没有水位线）：本轮本来就全部按「有变化」处理，
                        // 只要第 1 页的 maxSeen 来建立水位线就够了 —— 不必翻完所有页，
                        // 既省掉 9 次大响应，也不会因为撞上 maxPages 上限而永远建不起水位线。
                        if (watermark == null) {
                            val newest = items.firstNotNullOfOrNull { item ->
                                item.lastModified?.takeIf { KomgaWatermarks.parseLastModified(it) != null }
                            }
                            complete = true
                            empty = true
                            return@withTimeoutOrNull KomgaChangeSet(
                                changed = changed,
                                maxSeen = newest,
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
                            changed[item.id] = item.lastModified
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

        KomgaChangeSet(changed, maxSeen, complete, empty, pages = pagesFetched)
    }

    return collected ?: KomgaChangeSet(
        changed = emptyMap(),
        maxSeen = null,
        complete = false,
        empty = false,
        pages = pagesFetched,
        failureReason = "timeout after ${timeoutMillis}ms",
    )
}

/**
 * 一次查询的结局：交给 decider 的服务器结局 + 需要回退时的原因（正常为 null）。
 *
 * 两者判的是同一组分支，所以只在这里判一次 —— 否则很容易改了一处忘了另一处。
 * 回退原因会显示在更新页上，用户才知道这次为什么全量跑了一遍（超时 / 401 / 翻页被截断）。
 */
data class KomgaQueryOutcome(
    val outcome: KomgaServerOutcome,
    val fallbackReason: String?,
)

/**
 * 把一次查询结果换算成结局。
 *
 * 时间倒流（Komga 从备份恢复、最新系列被删）按规格 §6.4-7 处理：本次**全部**按「有变化」
 * （`empty = true` 让 decider 保留该服务器的所有条目），**并且**用本次的 maxSeen 推进水位线，
 * 否则水位线会永远卡在未来、SKIP 策略下所有 Komga 条目被永久跳过。
 */
fun queryOutcome(changeSet: KomgaChangeSet, watermark: LocalDateTime?): KomgaQueryOutcome = when {
    KomgaWatermarks.isBackwards(changeSet.maxSeen, watermark) -> KomgaQueryOutcome(
        outcome = KomgaServerOutcome.Ok(
            changedIds = emptySet(),
            maxSeen = changeSet.maxSeen,
            empty = true,
            times = changeSet.changed,
        ),
        fallbackReason = "watermark ahead of Komga",
    )

    changeSet.complete -> KomgaQueryOutcome(
        outcome = KomgaServerOutcome.Ok(
            changedIds = changeSet.changedIds,
            maxSeen = changeSet.maxSeen,
            empty = changeSet.empty,
            times = changeSet.changed,
        ),
        fallbackReason = null,
    )

    else -> {
        val reason = changeSet.failureReason ?: "incomplete result (page/time limit)"
        KomgaQueryOutcome(outcome = KomgaServerOutcome.Failed(reason), fallbackReason = reason)
    }
}
// KMK <--
