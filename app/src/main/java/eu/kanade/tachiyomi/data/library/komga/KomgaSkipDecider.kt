package eu.kanade.tachiyomi.data.library.komga

import tachiyomi.domain.library.service.LibraryPreferences.KomgaUnavailablePolicy

// KMK -->
/** 一条待更新条目在 Komga 视角下的身份；解析不出 baseUrl/seriesId 的就是「非增量条目」。 */
data class KomgaEntryRef(
    val mangaId: Long,
    val sourceId: Long,
    val baseUrl: String?,
    val seriesId: String?,
)

sealed interface KomgaServerOutcome {
    data class Ok(val changedIds: Set<String>, val maxSeen: String?, val empty: Boolean) : KomgaServerOutcome

    data class Failed(val reason: String) : KomgaServerOutcome
}

data class KomgaDecision(
    val keptIds: Set<Long>,
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
                } else {
                    skippedUnchangedIds += entry.mangaId
                }
            }
        }
    }

    val newWatermarks = outcomes.mapNotNull { (baseUrl, outcome) ->
        val maxSeen = (outcome as? KomgaServerOutcome.Ok)?.maxSeen
        if (maxSeen == null) null else baseUrl to maxSeen
    }.toMap()

    return KomgaDecision(
        keptIds = keptIds,
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
