package eu.kanade.tachiyomi.data.library.komga

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences.KomgaUnavailablePolicy
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.kmk.KMR

// KMK -->
/**
 * 增量更新的编排层：读配置 → 查各服务器的变更集合 → 交给 [decide] 换算成保留/跳过。
 *
 * 只在「整库更新 + 增量模式」时被调用；任何异常都不会让条目被少更新（出错就回退全量）。
 */
class KomgaIncrementalFilter(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {

    data class Outcome(
        val kept: List<LibraryManga>,
        val skipped: List<Pair<Manga, String>>,
        val newWatermarks: Map<String, String>,
        val ranIncremental: Boolean,
        /** Komga 明确报告「有变化」的条目数（更新页显示用）。 */
        val changedCount: Int = 0,
        /** 查询失败/异常导致回退全量时的原因（更新页显示用），正常时为 null。 */
        val fallbackReason: String? = null,
        /** 本次增量的完整数字（更新页始终显示，便于用户/开发者判断是否按预期工作）。 */
        val summary: String = "",
    )

    suspend fun apply(
        candidates: List<LibraryManga>,
        watermarksRaw: String,
        retryIdsRaw: String,
        policy: KomgaUnavailablePolicy,
    ): Outcome = try {
        run(candidates, watermarksRaw, retryIdsRaw, policy)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        // 承诺是「任何异常都不会少更新」：出错就保留全部条目，退回全量。
        // 但必须把原因带上，否则更新页什么都不显示，用户完全不知道这次为什么全量跑了一遍。
        logcat(LogPriority.WARN, e) { "Komga incremental: filter failed, falling back to full update" }
        Outcome(
            kept = candidates,
            skipped = emptyList(),
            newWatermarks = emptyMap(),
            ranIncremental = true,
            fallbackReason = "exception: ${e::class.simpleName}: ${e.message}",
        )
    }

    private suspend fun run(
        candidates: List<LibraryManga>,
        watermarksRaw: String,
        retryIdsRaw: String,
        policy: KomgaUnavailablePolicy,
    ): Outcome {
        val refs = candidates.map { parseKomgaSeriesUrl(it.manga.url) }
        val entries = candidates.mapIndexed { index, libraryManga ->
            KomgaEntryRef(
                mangaId = libraryManga.manga.id,
                sourceId = libraryManga.manga.source,
                baseUrl = refs[index]?.baseUrl,
                // id 不像 Komga 的 id（被编码过、含空格等）时不做增量判断，避免永久误跳过
                seriesId = refs[index]?.seriesId?.takeIf { isMatchableKomgaSeriesId(it) },
            )
        }

        val configCache = mutableMapOf<Long, KomgaSourceConfig?>()
        val servers = entries
            .mapNotNull { entry -> entry.baseUrl?.let { it to entry.sourceId } }
            .distinct()
            .sortedBy { (_, sourceId) -> sourceId }
            .mapNotNull { (baseUrl, sourceId) ->
                val config = configCache.getOrPut(sourceId) { readKomgaSourceConfig(context, sourceId) }
                    ?: return@mapNotNull null
                if (config.address != baseUrl) return@mapNotNull null
                baseUrl to config
            }
            .distinctBy { (baseUrl, _) -> baseUrl }
            .toMap()

        val watermarks = KomgaWatermarks.decode(watermarksRaw)
        val api = KomgaChangeSetClient(client, json)
        val outcomes = mutableMapOf<String, KomgaServerOutcome>()
        val fallbackReasons = mutableListOf<String>()
        var pagesTotal = 0

        for ((baseUrl, config) in servers) {
            val watermark = KomgaWatermarks.parseLastModified(watermarks[baseUrl])
            val changeSet = collectChangedSeries(
                watermark = watermark,
                fetchPage = { page -> api.fetchPage(baseUrl, config, page) },
            )
            outcomes[baseUrl] = toServerOutcome(changeSet, watermark)
            fallbackReason(changeSet, watermark)?.let { fallbackReasons += it }
            pagesTotal += changeSet.pages

            logcat(LogPriority.INFO) {
                "Komga incremental: server=$baseUrl pages=${changeSet.pages} " +
                    "changed=${changeSet.changedIds.size} complete=${changeSet.complete} " +
                    "empty=${changeSet.empty} watermark=${watermarks[baseUrl] ?: "none"} " +
                    "maxSeen=${changeSet.maxSeen ?: "none"} reason=${changeSet.failureReason ?: "none"}"
            }
        }

        val decision = decide(entries, outcomes, policy, parseRetryIds(retryIdsRaw))
        val unchangedReason = context.stringResource(KMR.strings.skipped_reason_komga_unchanged)
        val unavailableReason = context.stringResource(KMR.strings.skipped_reason_komga_unavailable)

        val kept = candidates.filter { it.manga.id in decision.keptIds }
        val skipped = candidates
            .filter {
                it.manga.id in decision.skippedUnchangedIds || it.manga.id in decision.skippedUnavailableIds
            }
            .map { libraryManga ->
                val reason = if (libraryManga.manga.id in decision.skippedUnchangedIds) {
                    unchangedReason
                } else {
                    unavailableReason
                }
                libraryManga.manga to reason
            }

        logcat(LogPriority.INFO) {
            "Komga incremental: servers=${servers.size} candidates=${candidates.size} " +
                "kept=${kept.size} skippedUnchanged=${decision.skippedUnchangedIds.size} " +
                "skippedUnavailable=${decision.skippedUnavailableIds.size} " +
                "retry=${parseRetryIds(retryIdsRaw).size} policy=$policy"
        }

        return Outcome(
            kept = kept,
            skipped = skipped,
            newWatermarks = decision.newWatermarks,
            ranIncremental = true,
            changedCount = decision.changedMangaIds.size,
            fallbackReason = fallbackReasons.takeIf { it.isNotEmpty() }?.joinToString("; "),
            summary = context.stringResource(
                KMR.strings.updates_komga_summary,
                servers.size,
                pagesTotal,
                decision.changedMangaIds.size,
                decision.skippedUnchangedIds.size + decision.skippedUnavailableIds.size,
            ),
        )
    }
}
// KMK <--
