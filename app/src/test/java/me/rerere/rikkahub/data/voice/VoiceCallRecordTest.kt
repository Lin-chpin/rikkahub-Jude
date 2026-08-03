package me.rerere.rikkahub.data.voice

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallRecordTest {
    @Test
    fun `pending ended event is consumed once`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = emptyList(),
                    annotations = listOf(
                        UIMessageAnnotation.VoiceCallRecord(
                            callId = "call-1",
                            durationSeconds = 30,
                            standalone = true,
                            cardAnchor = true,
                            pendingEndedEvent = true,
                        )
                    ),
                ).toMessageNode()
            ),
        )

        val firstConsumption = conversation.consumePendingVoiceCallEndedEvent()
        val secondConsumption = firstConsumption.conversation.consumePendingVoiceCallEndedEvent()

        assertTrue(firstConsumption.shouldNotifyModel)
        assertFalse(
            firstConsumption.conversation.messageNodes
                .single().currentMessage.voiceCallRecord()!!.pendingEndedEvent
        )
        assertFalse(secondConsumption.shouldNotifyModel)
    }

    @Test
    fun `voice call record is covered only after every linked message is covered`() {
        val userMessage = textMessage(MessageRole.USER, "hello")
        val assistantMessage = textMessage(MessageRole.ASSISTANT, "hi")
        val userNode = userMessage.toMessageNode()
        val assistantNode = assistantMessage.toMessageNode()
        val recordNode = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            annotations = listOf(
                UIMessageAnnotation.VoiceCallRecord(
                    callId = "call-2",
                    durationSeconds = 12,
                    standalone = true,
                    cardAnchor = true,
                    messageIds = setOf(
                        userMessage.id.toString(),
                        assistantMessage.id.toString(),
                    ),
                )
            ),
        ).toMessageNode()
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(userNode, assistantNode, recordNode),
        )

        assertTrue(
            conversation.voiceCallRecordNodeIdsFullyCoveredBy(setOf(userNode.id)).isEmpty()
        )
        assertEquals(
            setOf(recordNode.id),
            conversation.voiceCallRecordNodeIdsFullyCoveredBy(
                setOf(userNode.id, assistantNode.id)
            ),
        )
    }

    private fun textMessage(role: MessageRole, text: String) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
