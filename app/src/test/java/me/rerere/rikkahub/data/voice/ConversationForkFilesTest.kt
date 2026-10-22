package me.rerere.rikkahub.data.voice

import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.files.copyWithForkedFiles
import org.junit.Assert.*
import org.junit.Test

class ConversationForkFilesTest {
    @Test fun allRecordedAudioAndNestedAttachmentsGetIndependentFiles() {
        val recording = VoiceCallAudioSegment("spoken", "file:///call.mp3", "MP3", startPositionMs = 5, endPositionMs = 20)
        val source = UIMessage.assistant("hello").copy(
            parts = listOf(UIMessagePart.Tool("call", "image", "{}", listOf(UIMessagePart.Image("file:///image.png")))),
            annotations = listOf(
                UIMessageAnnotation.TtsAudio("hello", "hello", "file:///tts.mp3", "MP3"),
                UIMessageAnnotation.ChatVoiceReply(listOf(ChatVoiceReplySegment(ChatVoiceReplySegmentType.VOICE, "hello",
                    listOf(ChatVoiceAudioSegment("hello", "file:///voice.mp3", "MP3"))))),
                UIMessageAnnotation.VoiceCallRecord("record", 1, audioSegments = listOf(recording),
                    audioSegmentsByMessageId = mapOf("message" to listOf(recording))),
            ),
        )
        val fork = source.copyWithForkedFiles { it.replace("file:///", "file:///fork-") }
        assertEquals("file:///fork-image.png", ((fork.parts.single() as UIMessagePart.Tool).output.single() as UIMessagePart.Image).url)
        assertEquals("file:///fork-tts.mp3", (fork.annotations[0] as UIMessageAnnotation.TtsAudio).audioUri)
        assertEquals("file:///fork-voice.mp3", (fork.annotations[1] as UIMessageAnnotation.ChatVoiceReply).segments.single().audioSegments.single().audioUri)
        val record = fork.annotations[2] as UIMessageAnnotation.VoiceCallRecord
        assertEquals("file:///fork-call.mp3", record.audioSegments.single().audioUri)
        assertEquals(record.audioSegments, record.audioSegmentsByMessageId.getValue("message"))
        assertEquals(5L, record.audioSegments.single().startPositionMs)
        assertEquals("file:///call.mp3", (source.annotations[2] as UIMessageAnnotation.VoiceCallRecord).audioSegments.single().audioUri)
    }
}
