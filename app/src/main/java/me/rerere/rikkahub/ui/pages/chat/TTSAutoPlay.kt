package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.service.ChatRequestMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.voice.chatVoiceReply
import me.rerere.rikkahub.data.voice.hasChatVoiceReplyTool
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.rememberChatTtsPlayback
import me.rerere.rikkahub.utils.toChatTtsText
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.CachedAudioSource

@Composable
fun TTSAutoPlay(
    vm: ChatVM,
    setting: Settings,
    conversation: Conversation,
    onUpdateTtsMessage: (messageId: kotlin.uuid.Uuid, transform: (me.rerere.ai.ui.UIMessage) -> me.rerere.ai.ui.UIMessage) -> Unit,
) {
    // Auto-play TTS after generation completes
    val chatTts = rememberChatTtsPlayback()
    val tts = LocalTTSState.current
    val currentConversation by rememberUpdatedState(conversation)
    val updatedSetting by rememberUpdatedState(setting)
    LaunchedEffect(Unit) {
        vm.generationDoneFlow.collect { event ->
            if (event.conversationId != currentConversation.id || event.requestMode != ChatRequestMode.Normal) {
                return@collect
            }
            if (updatedSetting.displaySetting.autoPlayTTSAfterGeneration) {
                val lastMessage = currentConversation.currentMessages.lastOrNull()
                if (lastMessage != null && lastMessage.role == MessageRole.ASSISTANT) {
                    val assistantMessages = currentConversation.currentMessages
                        .asReversed()
                        .takeWhile { it.role == MessageRole.ASSISTANT }
                        .asReversed()

                    var isFirstSpeak = true
                    assistantMessages.forEach { message ->
                        message.chatVoiceReply()?.let { reply ->
                            val audios = reply.segments.flatMap { segment ->
                                segment.audioSegments.map { audio ->
                                    CachedAudioSource(
                                        audioUri = audio.audioUri,
                                        format = AudioFormat.valueOf(audio.format),
                                        sampleRate = audio.sampleRate,
                                    )
                                }
                            }
                            if (audios.isNotEmpty()) {
                                tts.playCachedAudios(audios, flushCalled = isFirstSpeak)
                                isFirstSpeak = false
                            }
                            return@forEach
                        }
                        if (message.hasChatVoiceReplyTool()) return@forEach
                        val text = message.toText()
                        val textToSpeak = text.toChatTtsText(
                            ttsOnlyReadQuoted = updatedSetting.displaySetting.ttsOnlyReadQuoted,
                            ttsEnglishOnly = updatedSetting.displaySetting.ttsEnglishOnly,
                        )
                        if (textToSpeak.isNotBlank()) {
                            chatTts.speak(
                                message = message,
                                text = textToSpeak,
                                onUpdateMessage = onUpdateTtsMessage,
                                flushCalled = isFirstSpeak,
                            )
                            isFirstSpeak = false
                        }
                    }
                }
            }
        }
    }
}
