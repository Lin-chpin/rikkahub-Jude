package me.rerere.rikkahub.data.files

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

/** A fork owns its files, including recordings nested in tool results and annotations. */
internal fun UIMessage.copyWithForkedFiles(copyFile: (String) -> String): UIMessage {
    fun copyPart(part: UIMessagePart): UIMessagePart = when (part) {
        is UIMessagePart.Image -> part.copy(url = copyFile(part.url))
        is UIMessagePart.Document -> part.copy(url = copyFile(part.url))
        is UIMessagePart.Video -> part.copy(url = copyFile(part.url))
        is UIMessagePart.Audio -> part.copy(url = copyFile(part.url))
        is UIMessagePart.Tool -> part.copy(output = part.output.map(::copyPart))
        else -> part
    }
    return copy(
        parts = parts.map(::copyPart),
        annotations = annotations.map { annotation ->
            when (annotation) {
                is UIMessageAnnotation.TtsAudio -> annotation.copy(audioUri = copyFile(annotation.audioUri))
                is UIMessageAnnotation.ChatVoiceReply -> annotation.copy(segments = annotation.segments.map { segment ->
                    segment.copy(audioSegments = segment.audioSegments.map { it.copy(audioUri = copyFile(it.audioUri)) })
                })
                is UIMessageAnnotation.VoiceCallRecord -> annotation.copy(
                    audioSegments = annotation.audioSegments.map { it.copy(audioUri = copyFile(it.audioUri)) },
                    audioSegmentsByMessageId = annotation.audioSegmentsByMessageId.mapValues { (_, segments) ->
                        segments.map { it.copy(audioUri = copyFile(it.audioUri)) }
                    },
                )
                else -> annotation
            }
        },
    )
}
