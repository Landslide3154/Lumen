package eu.kanade.tachiyomi.data.backup

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.BackupRestoreStatus
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.sync.SyncSectionCount
import eu.kanade.tachiyomi.data.sync.SyncText
import eu.kanade.tachiyomi.data.sync.SyncTrigger
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.TimeUnit

class BackupNotifier(private val context: Context) {

    private val preferences: SecurityPreferences by injectLazy()

    // KMK -->
    private val backupRestoreStatus: BackupRestoreStatus = Injekt.get()
    // KMK <--

    private val progressNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_BACKUP_RESTORE_PROGRESS,
    ) {
        setSmallIcon(R.drawable.ic_komikku)
        setColor(ContextCompat.getColor(context, R.color.ic_launcher))
        setAutoCancel(false)
        setOngoing(true)
        setOnlyAlertOnce(true)
    }

    private val completeNotificationBuilder = context.notificationBuilder(
        Notifications.CHANNEL_BACKUP_RESTORE_COMPLETE,
    ) {
        setSmallIcon(R.drawable.ic_komikku)
        setColor(ContextCompat.getColor(context, R.color.ic_launcher))
        setAutoCancel(false)
    }

    internal fun NotificationCompat.Builder.show(id: Int) {
        context.notify(id, build())
    }

    fun showBackupProgress(): NotificationCompat.Builder {
        val builder = with(progressNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.creating_backup))

            setProgress(0, 0, true)
        }

        // KMK -->
        // Avoid calling show() before returning builder for ForegroundInfo.
        // Calling show() here can cause duplicate notifications, as setForegroundSafely will display the notification using the returned builder.
        // builder.show(Notifications.ID_BACKUP_PROGRESS)
        // KMK <--

        return builder
    }

    fun showBackupError(error: String?) {
        context.cancelNotification(Notifications.ID_BACKUP_PROGRESS)

        with(completeNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.creating_backup_error))
            setContentText(error)

            show(Notifications.ID_BACKUP_COMPLETE)
        }
    }

    fun showBackupComplete(file: UniFile) {
        context.cancelNotification(Notifications.ID_BACKUP_PROGRESS)

        with(completeNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.backup_created))
            setContentText(file.displayablePath)

            clearActions()
            addAction(
                R.drawable.ic_share_24dp,
                context.stringResource(MR.strings.action_share),
                NotificationReceiver.shareBackupPendingActivity(context, file.uri),
            )

            show(Notifications.ID_BACKUP_COMPLETE)
        }
    }

    suspend fun showRestoreProgress(
        content: String = "",
        progress: Int = 0,
        maxAmount: Int = 100,
        sync: Boolean = false,
    ): NotificationCompat.Builder {
        val builder = with(progressNotificationBuilder) {
            val contentTitle = if (sync) {
                context.stringResource(MR.strings.syncing_library)
            } else {
                context.stringResource(MR.strings.restoring_backup)
            }
            setContentTitle(contentTitle)

            if (!preferences.hideNotificationContent().get()) {
                setContentText(content)
            }

            setProgress(maxAmount, progress, false)
            setOnlyAlertOnce(true)
            // KMK -->
            backupRestoreStatus.updateProgress(progress.toFloat() / maxAmount)
            // KMK <--

            clearActions()
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel),
                NotificationReceiver.cancelRestorePendingBroadcast(context, Notifications.ID_RESTORE_PROGRESS),
            )
        }

        // KMK -->
        // Avoid calling show() before returning builder for ForegroundInfo.
        // Calling show() here can cause duplicate notifications, as setForegroundSafely will display the notification using the returned builder.
        // builder.show(Notifications.ID_RESTORE_PROGRESS)
        // KMK <--

        return builder
    }

    fun showRestoreError(error: String?) {
        context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)

        with(completeNotificationBuilder) {
            setContentTitle(context.stringResource(MR.strings.restoring_backup_error))
            setContentText(error)

            show(Notifications.ID_RESTORE_COMPLETE)
        }
    }

    fun showRestoreComplete(
        time: Long,
        errorCount: Int,
        path: String?,
        file: String?,
        sync: Boolean,
        // KMK -->
        /** What the applied sync data carried, for the 「写进本机」 wording. */
        written: List<SyncSectionCount> = emptyList(),
        /** Why the sync ran, so both completion notifications name the same trigger. */
        trigger: SyncTrigger = SyncTrigger.UNKNOWN,
        // KMK <--
    ) {
        context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)

        with(completeNotificationBuilder) {
            if (sync) {
                // KMK -->
                // A sync wrote here what the server sent, so this notification says that it is the
                // local half of the sync instead of the upload the other one reports.
                setSyncWriteContent(time, errorCount, written, trigger)
                // KMK <--
            } else {
                val timeString = context.stringResource(
                    MR.strings.restore_duration,
                    TimeUnit.MILLISECONDS.toMinutes(time),
                    TimeUnit.MILLISECONDS.toSeconds(time) - TimeUnit.MINUTES.toSeconds(
                        TimeUnit.MILLISECONDS.toMinutes(time),
                    ),
                )

                setContentTitle(context.stringResource(MR.strings.restore_completed))
                setContentText(
                    context.pluralStringResource(
                        MR.plurals.restore_completed_message,
                        errorCount,
                        timeString,
                        errorCount,
                    ),
                )
            }

            clearActions()
            if (errorCount > 0 && !path.isNullOrEmpty() && !file.isNullOrEmpty()) {
                val destFile = File(path, file)
                val uri = destFile.getUriCompat(context)

                val errorLogIntent = NotificationReceiver.openErrorLogPendingActivity(context, uri)
                setContentIntent(errorLogIntent)
                addAction(
                    R.drawable.ic_folder_24dp,
                    context.stringResource(MR.strings.action_show_errors),
                    errorLogIntent,
                )
            }

            show(Notifications.ID_RESTORE_COMPLETE)
        }
    }

    // KMK -->
    /** The 「写进本机」 half of a sync: what was applied here, how long it took, and any errors. */
    private fun NotificationCompat.Builder.setSyncWriteContent(
        time: Long,
        errorCount: Int,
        written: List<SyncSectionCount>,
        trigger: SyncTrigger,
    ) {
        val duration = SyncText.duration(context, time)
        val line = written.takeIf { it.isNotEmpty() }
            ?.let { context.stringResource(KMR.strings.sync_detail_write, SyncText.sections(context, it)) }

        setContentTitle(context.stringResource(KMR.strings.sync_notif_title_local))
        setContentText(listOfNotNull(line, duration).joinToString(" · "))
        setStyle(
            NotificationCompat.BigTextStyle().bigText(
                buildList {
                    line?.let(::add)
                    add(duration)
                    if (trigger != SyncTrigger.UNKNOWN) {
                        add(context.stringResource(KMR.strings.sync_detail_trigger, SyncText.trigger(context, trigger)))
                    }
                    if (errorCount > 0) {
                        add(context.stringResource(KMR.strings.sync_detail_errors, errorCount))
                    }
                }.joinToString("\n"),
            ),
        )
    }
    // KMK <--
}
