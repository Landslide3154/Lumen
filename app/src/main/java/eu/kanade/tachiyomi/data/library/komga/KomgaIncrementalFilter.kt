package eu.kanade.tachiyomi.data.library.komga

import android.content.Context
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.KomgaUnavailablePolicy
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.kmk.KMR

// KMK -->
/**
 * 增量更新的编排层：读配置 → 查各服务器的变更集合 → 交给 [decide] 换算成保留/跳过。
 *
 * 只在「整库更新 + 增量模式」时被调用；任何异常都不会让条目被少更新（默认回退全量）。
 */
class KomgaIncrementalFilter(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val libraryPreferences: LibraryPreferences,
) {

    data class Outcome(
        val kept: List<LibraryManga>,
        val skipped: List<Pair<Manga, String>>,
        val newWatermarks: Map<String, String>,
        val ranIncremental: Boolean,
    )

    suspend fun apply(
        candidates: List<LibraryManga>,
        watermarksRaw: String,
        policy: KomgaUnavailablePolicy,
    ): Outcome {
        val refs = candidates.map { parseKomgaSeriesUrl(it.manga.url) }
        val entries = candidates.mapIndexed { index, libraryManga ->
            KomgaEntryRef(
                mangaId = libraryManga.manga.id,
                sourceId = libraryManga.manga.source,
                baseUrl = refs[index]?.baseUrl,
                seriesId = refs[index]?.seriesId,
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

        for ((baseUrl, config) in servers) {
            val watermark = KomgaWatermarks.parseLastModified(watermarks[baseUrl])
            val changeSet = collectChangedSeries(
                watermark = watermark,
                fetchPage = { page -> api.fetchPage(baseUrl, config, page) },
            )
            val backwards = KomgaWatermarks.isBackwards(changeSet.maxSeen, watermark)

            outcomes[baseUrl] = if (changeSet.complete && !backwards) {
                KomgaServerOutcome.Ok(changeSet.changedIds, changeSet.maxSeen, changeSet.empty)
            } else {
                KomgaServerOutcome.Failed(
                    when {
                        backwards -> "watermark ahead of Komga (server rolled back?)"
                        else -> "incomplete result (page/time limit or request failure)"
                    },
                )
            }

            logcat(LogPriority.INFO) {
                "Komga incremental: server=$baseUrl changed=${changeSet.changedIds.size} " +
                    "complete=${changeSet.complete} empty=${changeSet.empty} backwards=$backwards " +
                    "watermark=${watermarks[baseUrl] ?: "none"} maxSeen=${changeSet.maxSeen ?: "none"}"
            }
        }

        val decision = decide(entries, outcomes, policy)
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
                "skippedUnavailable=${decision.skippedUnavailableIds.size} policy=$policy"
        }

        return Outcome(
            kept = kept,
            skipped = skipped,
            newWatermarks = decision.newWatermarks,
            ranIncremental = true,
        )
    }
}
// KMK <--
