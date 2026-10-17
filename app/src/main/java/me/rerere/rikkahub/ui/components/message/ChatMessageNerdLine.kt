package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.toFixed
import java.time.Duration

/**
 * 显示消息的技术统计信息（如 token 使用量）
 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
    showTokenUsage: Boolean = LocalSettings.current.displaySetting.showTokenUsage,
) {
    if (!showTokenUsage || message.role != MessageRole.ASSISTANT) return

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = modifier.padding(horizontal = 4.dp),
            ) {
                val usage = message.usage
                if (usage != null) {
                    // Input tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Upload02,
                                contentDescription = "Input",
                                tint = color,
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${usage.promptTokens.formatNumber()} tokens")
                            // Cached tokens
                            Text(text = "(${usage.cachedTokens.formatNumber()} cached)")
                        }
                    )
                    // Output tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Download04,
                                contentDescription = "Output",
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${usage.completionTokens.formatNumber()} tokens")
                        }
                    )
                } else {
                    Text(stringResource(R.string.message_token_usage_unavailable))
                }
                // Elapsed time remains useful when an API omits token usage.
                val finishedAt = message.finishedAt
                if (finishedAt != null) {
                    val durationMillis = Duration.between(message.createdAt.toJavaLocalDateTime(), finishedAt.toJavaLocalDateTime()).toMillis().coerceAtLeast(0)
                    val seconds = (durationMillis / 1000f).toFixed(1)
                    if (usage != null && durationMillis > 0) {
                        val tps = usage.completionTokens.toFloat() / durationMillis * 1000
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Zap,
                                    contentDescription = "Speed",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = "${tps.toFixed(1)} tok/s")
                            }
                        )
                    }
                    StatsItem(
                        icon = {
                            Icon(imageVector = HugeIcons.Clock02, contentDescription = "Duration", modifier = Modifier.size(12.dp))
                        },
                        content = { Text(text = "${seconds}s") }
                    )
                } else {
                    Text(stringResource(R.string.message_duration_unavailable))
                }
            }
        }
    }
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}
