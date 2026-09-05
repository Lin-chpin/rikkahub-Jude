package me.rerere.rikkahub.personal.heartbeat

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

object HeartbeatNotifications {
    const val CHANNEL_ID = "personal_heartbeat"
    const val RUNNING_NOTIFICATION_ID = 47022
    private const val MESSAGE_NOTIFICATION_ID = 47023
    private const val QUESTION_NOTIFICATION_BASE = 47100

    fun createChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(
                CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH,
            )
                .setName(context.getString(R.string.heartbeat_notification_channel))
                .setVibrationEnabled(true)
                .build(),
        )
    }

    fun serviceNotification(context: Context, generating: Boolean): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(
                context.getString(
                    if (generating) R.string.heartbeat_running_title
                    else R.string.heartbeat_service_title,
                ),
            )
            .setContentText(
                context.getString(
                    if (generating) R.string.heartbeat_running_content
                    else R.string.heartbeat_service_waiting,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun updateServiceStatus(context: Context, generating: Boolean) {
        NotificationManagerCompat.from(context).notify(
            RUNNING_NOTIFICATION_ID,
            serviceNotification(context, generating),
        )
    }

    fun showQuestion(
        context: Context,
        conversationId: String,
        senderName: String,
        question: String,
    ) {
        notifyMessage(
            context = context,
            notificationId = QUESTION_NOTIFICATION_BASE + conversationId.hashCode(),
            conversationId = conversationId,
            title = context.getString(R.string.heartbeat_question_title, senderName),
            message = question,
        )
    }

    fun showMessage(
        context: Context,
        conversationId: String,
        senderName: String,
        message: String,
    ) {
        notifyMessage(
            context = context,
            notificationId = MESSAGE_NOTIFICATION_ID,
            conversationId = conversationId,
            title = senderName,
            message = message,
        )
    }

    private fun notifyMessage(
        context: Context,
        notificationId: Int,
        conversationId: String,
        title: String,
        message: String,
    ) {
        val contentIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            Intent(context, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversationId", conversationId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(title)
            .setContentText(message.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
