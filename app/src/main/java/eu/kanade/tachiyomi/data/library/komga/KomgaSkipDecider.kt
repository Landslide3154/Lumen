package eu.kanade.tachiyomi.data.library.komga

import tachiyomi.domain.library.service.LibraryPreferences.KomgaUnavailablePolicy
import java.time.LocalDateTime

// KMK -->
/** 一条待更新条目在 Komga 视角下的身份；解析不出 baseUrl/seriesId 的就是「非增量条目」。 */
data class KomgaEntryRef(
    val mangaId: Long,
    val sourceId: Long,
    val baseUrl: String?,
    val seriesId: String?,
)

sealed interface KomgaServerOutcome {
    /**
     * @param times 本次查询里每个有变化系列各自的时间（seriesId → lastModified）。
     *   水位线只允许推进到「本轮真正处理过」的条目上，所以必须带着这个映射。
     */
    data class Ok(
        val changedIds: Set<String>,
        val maxSeen: String?,
        val empty: Boolean,
        val times: Map<String, String?> = emptyMap(),
    ) : KomgaServerOutcome

    data class Failed(val reason: String) : KomgaServerOutcome
}

data class KomgaDecision(
    val keptIds: Set<Long>,
    /** 因为 Komga 报告「有变化」而保留的条目（更新页用来说明本次查到几部有变化）。 */
    val changedMangaIds: Set<Long>,
    val skippedUnchangedIds: Set<Long>,
    val skippedUnavailableIds: Set<Long>,
    /** 仅来自成功且拿到 maxSeen 的服务器。 */
    val newWatermarks: Map<String, String>,
)
/**
 * 纯函数：把「条目 × 各服务器结果 × 兜底策略」换算成「保留 / 跳过」。
 *
 * 判定顺序：非 Komga 条目 → 上次更新失败的条目（重试）→ 配置缺失 → 查询失败（按策略）
 * → Komga 上没有任何系列 / 时间倒流 → 是否在变更集合里。
 */
fun decide(
    entries: List<KomgaEntryRef>,
    outcomes: Map<String, KomgaServerOutcome>,
    policy: KomgaUnavailablePolicy,
    retryIds: Set<Long> = emptySet(),
): KomgaDecision {
    val keptIds = mutableSetOf<Long>()
    val changedMangaIds = mutableSetOf<Long>()
    val skippedUnchangedIds = mutableSetOf<Long>()
    val skippedUnavailableIds = mutableSetOf<Long>()

    for (entry in entries) {
        val baseUrl = entry.baseUrl
        val seriesId = entry.seriesId
        if (baseUrl == null || seriesId == null) {
            keptIds += entry.mangaId
            continue
        }

        // 上次更新失败过的条目强制重试一次，避免「一次瞬时失败 = 此后永不再自动更新」
        if (entry.mangaId in retryIds) {
            keptIds += entry.mangaId
            continue
        }

        when (val outcome = outcomes[baseUrl]) {
            null -> keptIds += entry.mangaId

            is KomgaServerOutcome.Failed -> {
                if (policy == KomgaUnavailablePolicy.FALLBACK_FULL) {
                    keptIds += entry.mangaId
                } else {
                    skippedUnavailableIds += entry.mangaId
                }
            }

            is KomgaServerOutcome.Ok -> {
                if (outcome.empty || seriesId in outcome.changedIds) {
                    keptIds += entry.mangaId
                    if (!outcome.empty && seriesId in outcome.changedIds) {
                        changedMangaIds += entry.mangaId
                    }
                } else {
                    skippedUnchangedIds += entry.mangaId
                }
            }
        }
    }

    // 水位线只推进到「本轮真正保留下来、会去更新」的条目的最大时间。
    // 直接拿服务器返回的全局 maxSeen，会把「本轮被分类设置/更新限制排除在外」的系列一起越过，
    // 那些系列从此每轮都算「无变化」被跳过（静默漏更新，且不会自愈）。
    val newWatermarks = outcomes.mapNotNull { (baseUrl, outcome) ->
        val ok = outcome as? KomgaServerOutcome.Ok ?: return@mapNotNull null

        // 时间倒流 / Komga 空库：该服务器本轮所有条目都被保留 → 可以直接推进到 maxSeen
        if (ok.empty) return@mapNotNull ok.maxSeen?.let { baseUrl to it }

        val newest = entries.asSequence()
            .filter { it.baseUrl == baseUrl && it.mangaId in keptIds }
            .mapNotNull { entry -> entry.seriesId?.let { ok.times[it] } }
            .maxByOrNull { KomgaWatermarks.parseLastModified(it) ?: LocalDateTime.MIN }
            ?: return@mapNotNull null

        baseUrl to newest
    }.toMap()

    return KomgaDecision(
        keptIds = keptIds,
        changedMangaIds = changedMangaIds,
        skippedUnchangedIds = skippedUnchangedIds,
        skippedUnavailableIds = skippedUnavailableIds,
        newWatermarks = newWatermarks,
    )
}

/**
 * seriesId 是否「看起来像 Komga 的 id」。Komga 用的是 UUID；带 `%`（被 URL 编码过）、
 * 含空格或超长的 id 说明 URL 与 Komga 的 id 对不上，此时**不做增量判断**（永远按有变化处理），
 * 避免因为一次对不上就永久跳过该条目。
 */
private val PLAUSIBLE_SERIES_ID = Regex("^[A-Za-z0-9._~-]{1,64}$")

fun isMatchableKomgaSeriesId(seriesId: String): Boolean = PLAUSIBLE_SERIES_ID.matches(seriesId)

/** 上次更新失败的条目 id，存成逗号分隔的字符串（应用状态偏好）。 */
fun parseRetryIds(raw: String): Set<Long> = raw.split(',')
    .mapNotNull { it.trim().toLongOrNull() }
    .toSet()

fun encodeRetryIds(ids: Set<Long>): String = ids.sorted().joinToString(",")
// KMK <--
