package space.linuxct.glyphworks.toy

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.util.Log
import space.linuxct.glyphworks.BaseApp
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.TimerSignalPort

class AndroidTimerSignal(private val app: Context) : TimerSignalPort {

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_CODE,
        Intent(app, TimerAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun scheduleAlarm(atEpochMillis: Long) {
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val onFire = pendingIntent()
        val backstopAt = atEpochMillis + BACKSTOP_SLACK_MS
        try {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, backstopAt, onFire)
            } else {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, backstopAt, WINDOW_MS, onFire)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm denied, using window", e)
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, backstopAt, WINDOW_MS, onFire)
        }
    }

    override fun cancelAlarm() {
        app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent())
    }

    override fun chime() {
        try {
            RingtoneManager.getRingtone(
                app,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            )?.play()
        } catch (e: Exception) {
            Log.w(TAG, "chime failed", e)
        }
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            postTimerNotification()
        }
    }

    private fun postTimerNotification() {
        try {
            val notificationManager = app.getSystemService(NotificationManager::class.java) ?: return
            val notification = Notification.Builder(app, BaseApp.CHANNEL_TIMER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.notif_timer_title))
                .setContentText(app.getString(R.string.notif_timer_body))
                .setAutoCancel(true)
                .build()
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "notification failed", e)
        }
    }

    private companion object {
        const val TAG = "TimerSignal"
        const val REQUEST_CODE = 1001
        const val NOTIFICATION_ID = 2001
        const val WINDOW_MS = 60_000L
        const val BACKSTOP_SLACK_MS = 3_000L
    }
}
