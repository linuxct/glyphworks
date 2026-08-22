package space.linuxct.glyphworks.ai

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import space.linuxct.glyphworks.App
import space.linuxct.glyphworks.R
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.ui.design.DesignEditorActivity

/**
 * Keeps the process alive while a turn runs. The type is `dataSync`, not `shortService`:
 * that one stops after three minutes and cannot extend, and slow turns are the point.
 * GlyphAiSession stops the service in a `finally`, so the six-hour daily cap is unreachable.
 */
class GlyphAiTurnService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val designId = intent?.getStringExtra(EXTRA_DESIGN_ID).orEmpty()
        val designName = intent?.getStringExtra(EXTRA_DESIGN_NAME).orEmpty()
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(designId, designName),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException and friends. The turn keeps
            // running as an ordinary background process.
            DebugLog.w(TAG, "could not go foreground: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    private fun buildNotification(designId: String, designName: String): Notification {
        val name = designName.ifBlank { getString(R.string.pref_custom_unnamed) }
        val builder = Notification.Builder(this, App.CHANNEL_AI)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_ai_turn_title))
            .setContentText(getString(R.string.notif_ai_turn_body, name))
            .setOngoing(true)
            .setProgress(0, 0, true)
        if (designId.isNotBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    DesignEditorActivity.intent(this, designId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "GlyphAiTurn"

        // Unique across the app: the Timer uses 2001 and the update check 2002.
        private const val NOTIFICATION_ID = 2003

        private const val EXTRA_DESIGN_ID = "designId"
        private const val EXTRA_DESIGN_NAME = "designName"

        fun intent(context: Context, designId: String, designName: String): Intent =
            Intent(context, GlyphAiTurnService::class.java)
                .putExtra(EXTRA_DESIGN_ID, designId)
                .putExtra(EXTRA_DESIGN_NAME, designName)
    }
}

/** The platform may refuse a foreground service. That costs protection, not the turn. */
class GlyphAiTurnNotifier(private val app: Context) : TurnForeground {

    override fun turnStarted(designId: String, designName: String) {
        try {
            app.startForegroundService(GlyphAiTurnService.intent(app, designId, designName))
        } catch (e: Exception) {
            DebugLog.w("GlyphAiTurn", "could not start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun turnEnded() {
        try {
            app.stopService(GlyphAiTurnService.intent(app, "", ""))
        } catch (e: Exception) {
            DebugLog.w("GlyphAiTurn", "could not stop: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
