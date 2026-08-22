package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.ui.ChatVoiceReplySegment
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.pages.chat.estimateVoiceCallDurationSeconds
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.CachedAudioSource
import me.rerere.tts.model.PlaybackStatus
import java.util.Locale

@Composable
internal fun ChatVoiceMessageBubble(
    segment: ChatVoiceReplySegment,
    onTranslate: ((Locale) -> Unit)?,
    onClearTranslation: (() -> Unit)?,
) {
    val tts = LocalTTSState.current
    val playbackState by tts.playbackState.collectAsStateWithLifecycle()
    val playbackSessionId by tts.playbackSessionId.collectAsStateWithLifecycle()
    var textExpanded by remember { mutableStateOf(false) }
    var voicePlaybackSessionId by remember { mutableStateOf<Long?>(null) }
    val audioReady = segment.audioSegments.isNotEmpty()
    val durationSeconds = remember(segment.text) {
        estimateVoiceCallDurationSeconds(segment.text)
    }
    val isCurrentPlayback = voicePlaybackSessionId != null &&
        voicePlaybackSessionId == playbackSessionId
    val currentPlaybackStatus = if (isCurrentPlayback) {
        playbackState.status
    } else {
        PlaybackStatus.Idle
    }
    val isPlaying = currentPlaybackStatus == PlaybackStatus.Playing
    val isPaused = currentPlaybackStatus == PlaybackStatus.Paused
    val playbackProgress = when {
        !isCurrentPlayback -> 0f
        currentPlaybackStatus == PlaybackStatus.Ended -> 1f
        playbackState.durationMs > 0L -> {
            (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
        }
        else -> 0f
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = {
                        when {
                            isPlaying -> tts.pause()
                            isPaused -> tts.resume()
                            audioReady -> {
                                textExpanded = true
                                voicePlaybackSessionId = tts.playCachedAudios(
                                    segment.audioSegments.map { audio ->
                                        CachedAudioSource(
                                            audioUri = audio.audioUri,
                                            format = AudioFormat.valueOf(audio.format),
                                            sampleRate = audio.sampleRate,
                                        )
                                    },
                                )
                            }
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) HugeIcons.Pause else HugeIcons.Play,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.chat_voice_reply_pause else R.string.chat_voice_reply_play,
                        ),
                        modifier = Modifier.size(19.dp),
                    )
                }
                ChatVoiceWaveform(
                    playbackStatus = currentPlaybackStatus,
                    progress = playbackProgress,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${durationSeconds}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!audioReady) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            if (audioReady) {
                TextButton(
                    onClick = { textExpanded = !textExpanded },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.MagicWand01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = stringResource(
                            if (textExpanded) R.string.chat_voice_reply_hide_text
                            else R.string.chat_voice_reply_show_text,
                        ),
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
