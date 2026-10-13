package eu.kanade.tachiyomi.data.sync

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class SyncNotifier(private val context: Context) {

    private val preferences: SecurityPreferences by injectLazy()

    // KMK -->
    private val syncStatus: SyncStatus = Injekt.get()
    // KMK <--

    private val progressNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_SYNC_LIBRARY,
    ) {
        setSmallIcon(R.drawable.ic_komikku)
        setColor(ContextCompat.getColor(context, R.color.ic_launcher))
        setAutoCancel(false)
        setOngoing(true)
        setOnlyAlertOnce(true)
    }

    private val completeNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_SYNC_LIBRARY,
    ) {
        setSmallIcon(R.drawable.ic_komikku)
        setColor(ContextCompat.getColor(context, R.color.ic_launcher))
        setAutoCancel(false)
    }

    private fun NotificationCompat.Builder.show(id: Int) {
        context.notify(id, build())
    }

    suspend fun showSyncProgress(
        content: String = "",
        progress: Int = 0,
        maxAmount: Int = 100,
    ): NotificationCompat.Builder {
        val builder = with(progressNotificationBuilder) {
            setContentTitle(context.getString(R.string.syncing_library))

            if (!preferences.hideNotificationContent().get()) {
                setContentText(content)
            }

            setProgress(maxAmount, progress, true)
            setOnlyAlertOnce(true)
            // KMK -->
            syncStatus.updateProgress(progress.toFloat() / maxAmount)
            // KMK <--

            clearActions()
            addAction(
                R.drawable.ic_close_24dp,
                context.getString(R.string.action_cancel),
                NotificationReceiver.cancelSyncPendingBroadcast(context, Notifications.ID_SYNC_PROGRESS),
            )
        }

        // KMK -->
        // Avoid calling show() before returning builder for ForegroundInfo.
        // Calling show() here can cause duplicate notifications, as setForegroundSafely will display the notification using the returned builder.
        // builder.show(Notifications.ID_SYNC_PROGRESS)
        // KMK <--

        return builder
    }

    fun showSyncError(error: String?) {
        context.cancelNotification(Notifications.ID_SYNC_PROGRESS)

        with(completeNotificationBuilder) {
            setContentTitle(context.getString(R.string.sync_error))
            setContentText(error)

            show(Notifications.ID_SYNC_COMPLETE)
        }
    }

    fun showSyncSuccess(summary: SyncSummary) {
        context.cancelNotification(Notifications.ID_SYNC_PROGRESS)

        // KMK -->
        val duration = SyncText.duration(context, summary.durationMs)
        val moved = if (summary.uploaded.isEmpty()) {
            context.stringResource(KMR.strings.sync_detail_no_upload)
        } else {
            context.stringResource(KMR.strings.sync_detail_upload, SyncText.sections(context, summary.uploaded))
        }
        // the collapsed line names a couple of sections; the expanded detail lists them all
        val collapsed = if (summary.uploaded.size > COLLAPSED_SECTIONS) {
            context.stringResource(KMR.strings.sync_detail_upload, collapsedSections(context, summary.uploaded))
        } else {
            moved
        }

        val detail = buildList {
            add(moved)
            add(context.stringResource(KMR.strings.sync_detail_mode, SyncText.mode(context, summary.full), duration))
            add(context.stringResource(KMR.strings.sync_detail_trigger, SyncText.trigger(context, summary.trigger)))
            if (summary.deletedCategories > 0) {
                add(
                    context.stringResource(
                        KMR.strings.sync_detail_deleted_categories,
                        summary.deletedCategories,
                    ),
                )
            }
            if (summary.remoteHadNothingNew) {
                add(context.stringResource(KMR.strings.sync_detail_nothing_new))
            }
        }.joinToString("\n")

        with(completeNotificationBuilder) {
            setContentTitle(context.stringResource(KMR.strings.sync_notif_title_server))
            setContentText("$collapsed · $duration")
            setStyle(NotificationCompat.BigTextStyle().bigText(detail))

            show(Notifications.ID_SYNC_COMPLETE)
        }
        // KMK <--
    }

    // KMK -->
    private fun collapsedSections(context: Context, counts: List<SyncSectionCount>): String {
        val head = SyncText.sections(context, counts.take(COLLAPSED_SECTIONS))
        return if (counts.size > COLLAPSED_SECTIONS) "$head…" else head
    }

    private companion object {
        const val COLLAPSED_SECTIONS = 2
    }
    // KMK <--
}
