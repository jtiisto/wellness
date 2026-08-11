package dev.jtiisto.wellness.hr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.jtiisto.wellness.R
import dev.jtiisto.wellness.core.ble.capture.HrCaptureState

/**
 * The ongoing notification a heart-rate capture runs behind.
 *
 * Device-only glue, excluded from the coverage gate; the text it renders is
 * [HrCaptureNotificationText], which is not.
 *
 * `IMPORTANCE_LOW` and `setSilent` because this fires once per capture and
 * updates several times a second — a channel that could make noise or peek
 * would be unusable. It is `setOngoing` because it is a foreground service's
 * notification and dismissing it would not stop the capture anyway.
 */
class HrCaptureNotification(private val context: Context) {

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Heart rate capture",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a heart-rate strap is recording"
            setShowBadge(false)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(state: HrCaptureState): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(HrCaptureNotificationText.TITLE)
        .setContentText(HrCaptureNotificationText.contentText(state))
        .setSubText(HrCaptureNotificationText.subText(state))
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(launchIntent())
        .build()

    /** Tapping it opens the app; there is nowhere else for it to go. */
    private fun launchIntent(): PendingIntent? = context.packageManager
        .getLaunchIntentForPackage(context.packageName)
        ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }

    companion object {
        const val CHANNEL_ID = "hr_capture"
        const val NOTIFICATION_ID = 1001
    }
}
