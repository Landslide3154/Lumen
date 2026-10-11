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
 * 判定顺序：非 Komga 条目 → 配置缺失 → 查询失败（按策略）→ Komga 上没有任何系列 → 是否在变更集合里。
 */
fun decide(
    entries: List<KomgaEntryRef>,
    outcomes: Map<String, KomgaServerOutcome>,
    policy: KomgaUnavailablePolicy,
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
// KMK <--
