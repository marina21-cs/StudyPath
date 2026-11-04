package com.studypath.app.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.StudyReminder
import com.studypath.app.domain.ReminderDeliveryGate
import com.studypath.app.domain.ReminderSchedulePolicy
import com.studypath.app.domain.ReminderScheduleIdentity
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    private const val PREFIX = "study_reminder_"
    private const val CANCELLED = -1L

    private fun key(uid: String, id: String) = PREFIX + uid + "_" + id
    private fun schedules(context: Context) = context.getSharedPreferences("reminder_schedule", Context.MODE_PRIVATE)
    private fun delivered(context: Context) = context.getSharedPreferences("reminder_delivered", Context.MODE_PRIVATE)
    private fun identities(context: Context) = context.getSharedPreferences("reminder_schedule_identity", Context.MODE_PRIVATE)
    private fun deliveredIdentities(context: Context) = context.getSharedPreferences("reminder_delivered_identity", Context.MODE_PRIVATE)
    internal fun notificationTag(uid: String, id: String): String = Uri.Builder().scheme("studypath").authority("reminder").appendPath(uid).appendPath(id).build().toString()

    private fun cancelNotification(context: Context, uid: String, id: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(notificationTag(uid, id), (uid + id).hashCode())
        manager.cancel((uid + id).hashCode()) // Clear a notification posted by the earlier untagged implementation.
    }

    fun allowed(context: Context): Boolean {
        val permission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel("study")
        return permission && NotificationManagerCompat.from(context).areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun reconcile(context: Context, uid: String?, reminders: List<StudyReminder>) {
        if (uid == null) return
        ReminderDeliveryGate.updateSchedule {
            val prefs = schedules(context)
            val active = ReminderSchedulePolicy.active(reminders).associateBy { key(uid, it.id) }
            prefs.all.keys.filter { it.startsWith(PREFIX + uid + "_") && it !in active }.forEach { name ->
                cancel(context, uid, name.removePrefix(PREFIX + uid + "_"))
            }
            active.values.forEach { schedule(context, uid, it) }
        }
    }

    /** Mirrors one queued local mutation without relying on a foreground observer. */
    fun update(context: Context, uid: String, id: String, reminder: StudyReminder?) = ReminderDeliveryGate.updateSchedule {
        if (reminder == null || !reminder.enabled) cancel(context, uid, id)
        else schedule(context, uid, reminder)
    }

    private fun cancel(context: Context, uid: String, id: String) {
        val prefs = schedules(context)
        val name = key(uid, id)
        if (prefs.contains(name) && prefs.getLong(name, CANCELLED) == CANCELLED && !identities(context).contains(name)) return
        // A tombstone also fences legacy jobs already starting during a deletion.
        check(identities(context).edit().remove(name).commit()) { "Unable to persist reminder target cancellation" }
        check(prefs.edit().putLong(name, CANCELLED).commit()) { "Unable to persist reminder cancellation" }
        WorkManager.getInstance(context).cancelUniqueWork(name)
        cancelNotification(context, uid, id)
    }

    private fun schedule(context: Context, uid: String, reminder: StudyReminder) {
        val prefs = schedules(context)
        val name = key(uid, reminder.id)
        val fingerprint = ReminderScheduleIdentity.fingerprint(reminder)
        val changed = prefs.getLong(name, CANCELLED) != reminder.dueAt || identities(context).getString(name, null) != fingerprint
        if (changed) {
            // Identity changes first: an old worker cannot post between the two durable writes.
            check(identities(context).edit().putString(name, fingerprint).commit()) { "Unable to persist reminder target" }
            check(prefs.edit().putLong(name, reminder.dueAt).commit()) { "Unable to persist reminder schedule" }
            WorkManager.getInstance(context).cancelUniqueWork(name)
            cancelNotification(context, uid, reminder.id)
        }
        val deliveredDue = delivered(context).let { if (it.contains(name)) it.getLong(name, 0) else null }
        val deliveredFingerprint = deliveredIdentities(context).getString(name, null)
        if (!allowed(context) || !ReminderScheduleIdentity.needsDelivery(reminder, deliveredFingerprint, deliveredDue)) return
        val work = OneTimeWorkRequestBuilder<StudyReminderWorker>()
            .setInitialDelay(ReminderSchedulePolicy.delayMillis(reminder.dueAt, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("uid" to uid, "id" to reminder.id, "scheduleIdentity" to fingerprint))
            .addTag(PREFIX + uid).build()
        // KEEP preserves delayed work after its due time and revives a finished,
        // permission-blocked attempt when notifications are allowed again.
        WorkManager.getInstance(context).enqueueUniqueWork(name,
            if (changed) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
    }
    fun clear(context: Context, uid: String) {
        ReminderDeliveryGate.updateSchedule {
            val prefs = schedules(context)
            val edit = prefs.edit()
            val identityEdit = identities(context).edit()
            prefs.all.keys.filter { it.startsWith(PREFIX + uid + "_") }.forEach { edit.putLong(it, CANCELLED); identityEdit.remove(it) }
            check(identityEdit.commit()) { "Unable to persist reminder target cancellation" }
            check(edit.commit()) { "Unable to persist reminder cancellation" }
            WorkManager.getInstance(context).cancelAllWorkByTag(PREFIX + uid)
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }
    }

    /** Called inside ReminderDeliveryGate, together with the final session check. */
    fun deliveryDue(context: Context, uid: String, id: String, expectedIdentity: String? = null): Long? {
        val prefs = schedules(context)
        val name = key(uid, id)
        // Retained reminders are reconciled into fresh work. Unidentified old jobs never post phantom alerts.
        if (!prefs.contains(name)) return null
        val due = prefs.getLong(name, CANCELLED)
        val previous = delivered(context).let { if (it.contains(name)) it.getLong(name, 0) else null }
        return due.takeIf { it != CANCELLED && ReminderScheduleIdentity.canDeliver(identities(context).getString(name, null),
            expectedIdentity, it, System.currentTimeMillis(), deliveredIdentities(context).getString(name, null), previous) }
    }

    fun markDelivered(context: Context, uid: String, id: String, due: Long, identity: String) {
        check(deliveredIdentities(context).edit().putString(key(uid, id), identity).commit()) { "Unable to persist reminder delivery identity" }
        check(delivered(context).edit().putLong(key(uid, id), due).commit()) {
            "Unable to persist reminder delivery"
        }
    }
}
class StudyReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.failure()
        val id = inputData.getString("id") ?: return Result.failure()
        val identity = inputData.getString("scheduleIdentity") ?: return Result.success()
        val repository = (applicationContext as StudyPathApplication).graph.repository
        if (FirebaseAuth.getInstance().currentUser?.uid != uid || repository.boundUid() != uid || !ReminderScheduler.allowed(applicationContext)) return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("study", "Study reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.parse(ReminderScheduler.notificationTag(uid, id)))
            .setAction("com.studypath.app.OPEN_REMINDER.${(uid + id).hashCode()}").putExtra("reminderId", id)
        val pending = PendingIntent.getActivity(applicationContext, (uid + id).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "study")
            .setSmallIcon(android.R.drawable.ic_menu_agenda).setContentTitle("Time for a study session")
            .setContentText("Open StudyPath to continue your review.").setContentIntent(pending)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).build()
        var due: Long? = null
        ReminderDeliveryGate.deliverIf(
            { !isStopped && FirebaseAuth.getInstance().currentUser?.uid == uid &&
                repository.boundUid() == uid && ReminderScheduler.allowed(applicationContext) &&
                ReminderScheduler.deliveryDue(applicationContext, uid, id, identity).also { due = it } != null },
            {
                manager.notify(ReminderScheduler.notificationTag(uid, id), (uid + id).hashCode(), notification)
                ReminderScheduler.markDelivered(applicationContext, uid, id, checkNotNull(due), identity)
            },
        )
        return Result.success()
    }
}
