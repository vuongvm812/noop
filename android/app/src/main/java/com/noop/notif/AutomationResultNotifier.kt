package com.noop.notif

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noop.R
import com.noop.analytics.AutomationResultPolicy
import com.noop.ui.NoopPrefs
import com.noop.ui.appLaunchIntent
import com.noop.ui.logicalDayKeyNow

/**
 * The result notifications of the background automations (#92): a detected nap waiting for review,
 * an automatic backup finishing or failing, a self-hosted push finishing or failing, and a new
 * cycle-awareness reading. Each has its own default-off toggle ([NoopPrefs]) and a pure gate in
 * [AutomationResultPolicy]. Rhythm instrumentation never posts here (docs/SCOPE.md).
 *
 * Notification ids continue the map in [ScheduledReportNotifier]: 4216 nap, 4217 backup, 4218 push,
 * 4219 cycle reading.
 */
object AutomationResultNotifier {
    private const val CHANNEL_ID = "noop_automation_results"
    private const val NAP_NOTIF_ID = 4216
    private const val BACKUP_NOTIF_ID = 4217
    private const val PUSH_NOTIF_ID = 4218
    private const val CYCLE_NOTIF_ID = 4219

    /**
     * A nap was just queued for review (its start, epoch seconds). Notifies only for a nap strictly
     * newer than the last one notified, so a re-run of the detector never repeats itself.
     */
    fun onNapQueued(context: Context, napStartTs: Long, minutes: Long) {
        if (!NoopPrefs.napResultEnabled(context) || napStartTs <= NoopPrefs.napResultLastTs(context)) return
        if (post(
                context, NAP_NOTIF_ID,
                context.getString(R.string.automation_nap_title),
                context.getString(R.string.automation_nap_body, minutes),
            )
        ) NoopPrefs.setNapResultLastTs(context, napStartTs)
    }

    /** An automatic backup finished ([succeeded]) or failed. Manual backups report on screen instead. */
    fun onBackupResult(context: Context, succeeded: Boolean) = onJobResult(
        context, succeeded, NoopPrefs.backupResultEnabled(context), JobKeys.BACKUP, BACKUP_NOTIF_ID,
        if (succeeded) R.string.automation_backup_done_title else R.string.automation_backup_failed_title,
        if (succeeded) R.string.automation_backup_done_body else R.string.automation_backup_failed_body,
    )

    /** A self-hosted push run settled as a success or a failure (retries are not results). */
    fun onPushResult(context: Context, succeeded: Boolean) = onJobResult(
        context, succeeded, NoopPrefs.pushResultEnabled(context), JobKeys.PUSH, PUSH_NOTIF_ID,
        if (succeeded) R.string.automation_push_done_title else R.string.automation_push_failed_title,
        if (succeeded) R.string.automation_push_done_body else R.string.automation_push_failed_body,
    )

    /** The cycle-awareness engine produced [phase] (its raw key). Neutral copy, never the phase itself. */
    fun onCycleReading(context: Context, phase: String?) {
        if (!AutomationResultPolicy.shouldNotifyCycleReading(
                enabled = NoopPrefs.cycleResultEnabled(context),
                phase = phase,
                lastNotifiedPhase = NoopPrefs.cycleResultLastPhase(context),
            )
        ) return
        if (post(
                context, CYCLE_NOTIF_ID,
                context.getString(R.string.automation_cycle_title),
                context.getString(R.string.automation_cycle_body),
            )
        ) phase?.let { NoopPrefs.setCycleResultLastPhase(context, it) }
    }

    private enum class JobKeys { BACKUP, PUSH }

    private fun onJobResult(
        context: Context,
        succeeded: Boolean,
        enabled: Boolean,
        job: JobKeys,
        id: Int,
        titleRes: Int,
        bodyRes: Int,
    ) {
        val day = logicalDayKeyNow()
        val outstanding = NoopPrefs.jobFailureOutstanding(context, job.name)
        val notify = AutomationResultPolicy.shouldNotifyResult(
            enabled = enabled,
            succeeded = succeeded,
            day = day,
            lastSuccessDay = NoopPrefs.jobLastSuccessDay(context, job.name),
            failureOutstanding = outstanding,
        )
        // A success always clears the failure state, notified or not; a failure is marked only once told,
        // so one that could not be posted (notifications off) is still told on the next failure.
        val posted = notify && post(context, id, context.getString(titleRes), context.getString(bodyRes))
        if (succeeded) {
            NoopPrefs.setJobFailureOutstanding(context, job.name, false)
            if (posted) NoopPrefs.setJobLastSuccessDay(context, job.name, day)
        } else if (posted) {
            NoopPrefs.setJobFailureOutstanding(context, job.name, true)
        }
    }

    /** Post on the automation-results channel; true when it was handed to the system. */
    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled()
    private fun post(context: Context, id: Int, title: String, body: String): Boolean = runCatching {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        ensureChannel(context)
        val openApp = PendingIntent.getActivity(
            context, 3, appLaunchIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
        true
    }.getOrDefault(false)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Re-creating an existing channel only refreshes its name and description (importance and the
        // user\'s own settings are kept), so the channel follows the app language.
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, context.getString(R.string.automation_channel_name), NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.automation_channel_description) },
        )
    }
}
