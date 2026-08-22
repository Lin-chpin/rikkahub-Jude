package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.ChatVoiceReplySegment
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Voice
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.pages.chat.estimateVoiceCallDurationSeconds
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.CachedAudioSource
import java.util.Locale

@Composable
internal fun ChatVoiceMessageBubble(
    segment: ChatVoiceReplySegment,
    onTranslate: ((Locale) -> Unit)?,
    onClearTranslation: (() -> Unit)?,
) {
    val tts = LocalTTSState.current
    var textExpanded by remember { mutableStateOf(false) }
    val audioReady = segment.audioSegments.isNotEmpty()
    val durationSeconds = remember(segment.text) {
        estimateVoiceCallDurationSeconds(segment.text)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Voice,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "语音消息",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${durationSeconds}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (audioReady) {
                    IconButton(
                        onClick = {
                            textExpanded = true
                            tts.playCachedAudios(
                                segment.audioSegments.map { audio ->
                                    CachedAudioSource(
                                        audioUri = audio.audioUri,
                                        format = AudioFormat.valueOf(audio.format),
                                        sampleRate = audio.sampleRate,
                                    )
                                }
                            )
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.VolumeHigh,
                            contentDescription = "播放语音消息",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            AnimatedVisibility(
                visible = textExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = segment.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onTranslate != null && onClearTranslation != null) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TranslateMessageButton(
                                onTranslate = onTranslate,
                                onClearTranslation = onClearTranslation,
                                showLabel = true,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    segment.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                        CollapsibleTranslationText(
                            content = translation,
                            onClickCitation = {},
                            showHeader = false,
                            showCollapseControl = false,
                        )
                    }
                }
            }
        }
    }
}
