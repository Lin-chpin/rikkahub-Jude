package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.utils.NotificationConfig
import me.rerere.rikkahub.utils.NotificationUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.cancelNotification

object VoiceCallNotifications {
    private const val NOTIFICATION_ID_BASE = 30_000

    fun show(
        context: Context,
        conversationId: String,
        senderName: String,
        reasonPayload: String,
        channelId: String = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
        toolCallId: String? = null,
    ) {
        val invitation = VoiceCallInviteDeadlines.begin(context, conversationId, toolCallId)
        val reason = runCatching {
            Json.parseToJsonElement(reasonPayload)
                .jsonObject["reason"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
        }.getOrNull().orEmpty().ifBlank {
            context.getString(R.string.notification_voice_call_default_reason)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            Intent(context, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversationId", conversationId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notificationConfig = NotificationConfig().apply {
            title = context.getString(R.string.notification_voice_call_title, senderName)
            content = reason
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_CALL
            useBigTextStyle = true
            this.contentIntent = contentIntent
        }
        if (NotificationUtil.hasNotificationPermission(context)) {
            NotificationManagerCompat.from(context).notify(notificationId(conversationId),
                NotificationUtil.buildNotification(context, channelId, notificationConfig)
                    .setTimeoutAfter(invitation.remainingMillis(System.currentTimeMillis()).coerceAtLeast(1))
                    .build())
        }
    }

    fun cancel(context: Context, conversationId: String) {
        context.cancelNotification(notificationId(conversationId))
    }

    private fun notificationId(conversationId: String): Int =
        NOTIFICATION_ID_BASE + (conversationId.hashCode() and 0x7fff)
}
