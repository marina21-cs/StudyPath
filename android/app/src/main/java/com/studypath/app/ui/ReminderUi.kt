package com.studypath.app.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar

internal object ReminderUi {
    fun pickFutureTime(context: Context, initialAt: Long, selected: (Long, String) -> Unit) {
        val date = Calendar.getInstance().apply {
            timeInMillis = initialAt.takeIf { it > System.currentTimeMillis() }
                ?: System.currentTimeMillis() + 30 * 60_000L
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        DatePickerDialog(context, { _, year, month, day ->
            date.set(year, month, day)
            TimePickerDialog(context, { _, hour, minute ->
                date.set(Calendar.HOUR_OF_DAY, hour)
                date.set(Calendar.MINUTE, minute)
                if (date.timeInMillis <= System.currentTimeMillis()) {
                    MaterialAlertDialogBuilder(context).setMessage("Choose a future time.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Choose again") { _, _ -> pickFutureTime(context, date.timeInMillis, selected) }
                        .show()
                } else selected(date.timeInMillis, date.timeZone.id)
            }, date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(context)).show()
        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            datePicker.maxDate = 4_102_444_800_000L
        }.show()
    }

    fun showNotificationSettings(fragment: Fragment) {
        val context = fragment.context ?: return
        if (!fragment.isAdded || fragment.view == null) return
        val runtimeAllowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val appAllowed = runtimeAllowed && NotificationManagerCompat.from(context).areNotificationsEnabled()
        val channelBlocked = appAllowed && context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel("study")?.importance == NotificationManager.IMPORTANCE_NONE
        val message = if (channelBlocked)
            "Notifications are off for the Study reminders channel. Enable this channel in Android settings, then try again."
        else "Notifications are off for StudyPath. Enable them in Android settings, then try again."
        MaterialAlertDialogBuilder(context).setTitle("Enable study notifications")
            .setMessage(message).setNegativeButton("Cancel", null)
            .setPositiveButton("Open settings") { _, _ ->
                val intent = if (channelBlocked) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "study")
                else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                try { fragment.startActivity(intent) }
                catch (_: ActivityNotFoundException) {
                    fragment.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }
            }.show()
    }
}
