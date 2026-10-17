package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.voice.VOICE_CALL_UNAVAILABLE_MESSAGE
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

internal const val VOICE_CALL_INVITE_TIMEOUT_MILLIS = 30_000L

@Serializable
data class VoiceCallInviteDeadline(
    val id: String,
    val conversationId: String,
    val toolCallId: String? = null,
    val initiatedAtMillis: Long,
    val status: String = "pending",
) {
    val expiresAtMillis get() = initiatedAtMillis + VOICE_CALL_INVITE_TIMEOUT_MILLIS
    fun remainingMillis(now: Long) = (expiresAtMillis - now).coerceIn(0, VOICE_CALL_INVITE_TIMEOUT_MILLIS)
    fun effectiveStatus(now: Long) = if (status == "pending" && now >= expiresAtMillis) "missed" else status
}

/** Invitation lifetime belongs to the call, never to the activity that displays it. */
object VoiceCallInviteDeadlines {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private fun preferences(context: Context) = context.getSharedPreferences("voice_call_invitations", Context.MODE_PRIVATE)
    private fun read(context: Context, id: String): VoiceCallInviteDeadline? = runCatching {
        preferences(context).getString("invite:$id", null)?.let { json.decodeFromString<VoiceCallInviteDeadline>(it) }
    }.getOrNull()

    fun begin(context: Context, conversationId: String, toolCallId: String?): VoiceCallInviteDeadline = synchronized(lock) {
        val id = toolCallId ?: Uuid.random().toString()
        val invite = read(context, id) ?: VoiceCallInviteDeadline(id, conversationId, toolCallId, System.currentTimeMillis())
        val prefs = preferences(context)
        prefs.getString("latest:$conversationId", null)?.takeIf { it != id }?.let { oldId ->
            read(context, oldId)?.let { old -> save(context, old.copy(status = "superseded")) }
            if (read(context, oldId)?.toolCallId == null) prefs.edit().remove("invite:$oldId").apply()
        }
        save(context, invite)
        prefs.edit().putString("latest:$conversationId", id).apply()
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, VoiceCallInviteTimeoutReceiver::class.java)
            .setData(Uri.parse("rikkahub://voice-call-timeout/${Uri.encode(id)}"))
            .putExtra("invitationId", id)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, invite.expiresAtMillis, pending)
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, invite.expiresAtMillis, pending)
        Log.i("VoiceCallFlow", "invitation started id=$id timeoutMs=$VOICE_CALL_INVITE_TIMEOUT_MILLIS")
        invite
    }

    fun forTool(context: Context, toolCallId: String): VoiceCallInviteDeadline? = synchronized(lock) { read(context, toolCallId) }
    fun latest(context: Context, conversationId: String): VoiceCallInviteDeadline? = synchronized(lock) {
        preferences(context).getString("latest:$conversationId", null)?.let { read(context, it) }
    }
    fun complete(context: Context, toolCallId: String, answered: Boolean, reason: String) = synchronized(lock) {
        val invite = read(context, toolCallId) ?: return@synchronized
        if (invite.status != "pending" && invite.status != "missed") return@synchronized
        val status = when {
            invite.effectiveStatus(System.currentTimeMillis()) == "missed" -> "missed"
            answered -> "answered"
            reason.contains("not answered", ignoreCase = true) -> "missed"
            reason == VOICE_CALL_UNAVAILABLE_MESSAGE -> "failed"
            else -> "declined"
        }
        save(context, invite.copy(status = status))
        Log.i("VoiceCallFlow", "invitation resolved id=${invite.id} status=$status")
    }

    fun expire(context: Context, id: String) = synchronized(lock) {
        val invite = read(context, id) ?: return@synchronized
        if (invite.status != "pending" || System.currentTimeMillis() < invite.expiresAtMillis) return@synchronized
        save(context, invite.copy(status = "missed"))
        VoiceCallNotifications.cancel(context, invite.conversationId)
        runCatching { invite.toolCallId?.let { toolId ->
            val service = GlobalContext.get().get<ChatService>()
            val conversationId = Uuid.parse(invite.conversationId)
            val pending = service.getConversationFlow(conversationId).value.currentMessages.any { message ->
                message.parts.any { it is UIMessagePart.Tool && it.toolName == "request_voice_call" && it.toolCallId == toolId && it.isPending }
            }
            if (pending) service.handleToolApproval(conversationId, toolId, false, "The voice call was not answered within 30 seconds.")
        } }.onFailure { Log.w("VoiceCallFlow", "expiry receipt deferred id=$id error=${it::class.simpleName}") }
        Log.i("VoiceCallFlow", "invitation expired id=$id")
    }
    private fun save(context: Context, invite: VoiceCallInviteDeadline) {
        preferences(context).edit().putString("invite:${invite.id}", json.encodeToString(VoiceCallInviteDeadline.serializer(), invite)).commit()
    }
}

class VoiceCallInviteTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        intent?.getStringExtra("invitationId")?.let { VoiceCallInviteDeadlines.expire(context, it) }
    }
}
