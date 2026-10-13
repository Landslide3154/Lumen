package eu.kanade.tachiyomi.data.sync

import android.content.Context
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR

/**
 * The wording both sync completion notifications share, so the one that talks about the server and
 * the one that talks about this device read the same way.
 */
internal object SyncText {

    /** "书架 12 部 · 分类 3 个", or an empty string when nothing moved. */
    fun sections(context: Context, counts: List<SyncSectionCount>): String =
        counts.joinToString(" · ") { section(context, it) }

    private fun section(context: Context, count: SyncSectionCount): String = when (count.section) {
        SyncSection.MANGA -> context.stringResource(KMR.strings.sync_item_manga, count.count)
        SyncSection.CHAPTER -> context.stringResource(KMR.strings.sync_item_chapter, count.count)
        SyncSection.CATEGORY -> context.stringResource(KMR.strings.sync_item_category, count.count)
        SyncSection.SETTING -> context.stringResource(KMR.strings.sync_item_setting, count.count)
        SyncSection.SOURCE_SETTING -> context.stringResource(KMR.strings.sync_item_source_setting, count.count)
        SyncSection.SAVED_SEARCH -> context.stringResource(KMR.strings.sync_item_saved_search, count.count)
        SyncSection.FEED -> context.stringResource(KMR.strings.sync_item_feed, count.count)
        SyncSection.EXTENSION_STORE -> context.stringResource(KMR.strings.sync_item_extension_store, count.count)
    }

    fun mode(context: Context, full: Boolean): String =
        context.stringResource(if (full) KMR.strings.sync_mode_full else KMR.strings.sync_mode_delta)

    fun duration(context: Context, durationMs: Long): String =
        context.stringResource(KMR.strings.sync_duration, seconds(durationMs))

    /** Rounded to whole seconds, never "0 秒": the sync did take a moment either way. */
    private fun seconds(durationMs: Long): Long = ((durationMs + 500) / 1000).coerceAtLeast(1)

    fun trigger(context: Context, trigger: SyncTrigger): String = when (trigger) {
        SyncTrigger.MANUAL -> context.stringResource(KMR.strings.sync_trigger_manual)
        SyncTrigger.BEFORE_LIBRARY_UPDATE -> context.stringResource(KMR.strings.sync_trigger_before_update)
        SyncTrigger.SCHEDULED -> context.stringResource(KMR.strings.sync_trigger_scheduled)
        SyncTrigger.APP_START -> context.stringResource(KMR.strings.sync_trigger_app_start)
        SyncTrigger.APP_RESUME -> context.stringResource(KMR.strings.sync_trigger_app_resume)
        SyncTrigger.CHAPTER_OPEN -> context.stringResource(KMR.strings.sync_trigger_chapter_open)
        SyncTrigger.CHAPTER_READ -> context.stringResource(KMR.strings.sync_trigger_chapter_read)
        SyncTrigger.UNKNOWN -> context.stringResource(KMR.strings.sync_trigger_unknown)
    }
}
