package me.rerere.rikkahub.data.voice

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.messagesForGeneration
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

    @Test
    fun `call summary replaces transcript in generation context and keeps original messages`() {
        val userMessage = textMessage(MessageRole.USER, "通话原文内容")
        val assistantMessage = textMessage(MessageRole.ASSISTANT, "通话回复内容")
        val followUpMessage = textMessage(MessageRole.USER, "后续问题")
        val recordNode = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            annotations = listOf(
                UIMessageAnnotation.VoiceCallRecord(
                    callId = "call-summary",
                    durationSeconds = 20,
                    standalone = true,
                    cardAnchor = true,
                    messageIds = setOf(userMessage.id.toString(), assistantMessage.id.toString()),
                )
            ),
        ).toMessageNode()
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                userMessage.toMessageNode(),
                assistantMessage.toMessageNode(),
                recordNode,
                followUpMessage.toMessageNode(),
            ),
        )

        assertEquals(
            listOf(userMessage, assistantMessage, followUpMessage),
            conversation.messagesForGeneration(),
        )

        val summary = "通话讨论了项目进度，并决定周五前完成修改。"
        val summarized = conversation.withStandaloneVoiceCallSummary("call-summary", summary)!!

        assertEquals(listOf(followUpMessage), summarized.messagesForGeneration())
        assertTrue(summarized.voiceCallSummaryPrompt()!!.contains(summary))
        assertFalse(summarized.voiceCallSummaryPrompt()!!.contains("通话原文内容"))
        assertTrue(summarized.messageNodes.any { node ->
            node.messages.any { it.id == userMessage.id || it.id == assistantMessage.id }
        })
    }

    private fun textMessage(role: MessageRole, text: String) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
