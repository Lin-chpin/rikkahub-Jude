package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.GoogleAuthType
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Time02
import me.rerere.rikkahub.R
import kotlin.math.roundToInt
import me.rerere.rikkahub.data.ai.gemini.GeminiOAuthAccountRepository
import me.rerere.rikkahub.data.ai.gemini.GeminiOAuthQuotaBucket
import me.rerere.rikkahub.data.ai.gemini.GeminiOAuthQuotaGroup
import me.rerere.rikkahub.data.ai.openai.OpenAICodexAuthService
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatQuotaResetTimeInShanghai
import org.koin.compose.koinInject

internal data class QuotaRow(
    val title: String,
    val remainingFraction: Float?,
    val resetTime: String?,
)

internal fun List<GeminiOAuthQuotaGroup>.toQuotaRows(): List<QuotaRow> = flatMap { group ->
    group.buckets.map { bucket ->
        QuotaRow(
            title = formatGeminiQuotaRowTitle(group.displayName, bucket),
            remainingFraction = bucket.remainingFraction?.toFloat(),
            resetTime = bucket.resetTime?.let(::formatQuotaResetTimeInShanghai),
        )
    }
}

internal fun formatGeminiQuotaRowTitle(
    groupDisplayName: String,
    bucket: GeminiOAuthQuotaBucket,
): String {
    val groupName = groupDisplayName.trim()
    val bucketName = bucket.displayName.trim()
    val baseTitle = when {
        groupName.isNotEmpty() && bucketName.isNotEmpty() && !bucketName.equals(groupName, ignoreCase = true) ->
            "$groupName · $bucketName"
        bucketName.isNotEmpty() -> bucketName
        else -> groupName
    }
    val windowLabel = bucket.window?.trim()?.takeIf(String::isNotEmpty) ?: return baseTitle
    if (titleMentionsWindow(baseTitle, windowLabel)) return baseTitle
    return if (baseTitle.isEmpty()) windowLabel else "$baseTitle ($windowLabel)"
}

private fun titleMentionsWindow(title: String, windowLabel: String): Boolean {
    if (title.contains(windowLabel, ignoreCase = true)) return true
    val aliases = when (windowLabel.lowercase()) {
        "5h" -> listOf("5-hour", "5 hour", "five hour", "five-hour", "5小时", "5 小时")
        "weekly", "7d", "1w" -> listOf("weekly", "7d", "1w", "week", "7天", "7 天", "每周")
        "24h", "daily", "1d" -> listOf("24h", "daily", "1d", "每日", "24 小时")
        else -> emptyList()
    }
    return aliases.any { title.contains(it, ignoreCase = true) }
}

@Composable
internal fun SubscriptionQuotaButton(model: Model?) {
    val settings = LocalSettings.current
    val provider = model?.findProvider(settings.providers)
    val isGeminiSubscription = provider is ProviderSetting.Google &&
        provider.authType == GoogleAuthType.GEMINI_SUBSCRIPTION
    val isChatGptSubscription = provider is ProviderSetting.OpenAI &&
        provider.authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION
    if (!isGeminiSubscription && !isChatGptSubscription) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val geminiAccountRepository = koinInject<GeminiOAuthAccountRepository>()
    val codexAuthService = koinInject<OpenAICodexAuthService>()
    var showDialog by remember(provider?.id) { mutableStateOf(false) }
    var loading by remember(provider?.id) { mutableStateOf(false) }
    var rows by remember(provider?.id) { mutableStateOf<List<QuotaRow>>(emptyList()) }
    var errorText by remember(provider?.id) { mutableStateOf<String?>(null) }

    fun refreshQuota() {
        if (loading) return
        scope.launch {
            loading = true
            errorText = null
            try {
                rows = when (val selectedProvider = provider) {
                    is ProviderSetting.Google -> geminiAccountRepository.fetchQuota().toQuotaRows()

                    is ProviderSetting.OpenAI -> codexAuthService.fetchQuota(selectedProvider).map { window ->
                        QuotaRow(
                            title = window.displayName,
                            remainingFraction = window.remainingPercent?.div(100f),
                            resetTime = window.resetTime?.let(::formatQuotaResetTimeInShanghai),
                        )
                    }

                    else -> emptyList()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                errorText = error.message ?: context.getString(
                    if (isGeminiSubscription) R.string.gemini_oauth_quota_failed
                    else R.string.chatgpt_subscription_quota_failed,
                )
            } finally {
                loading = false
            }
        }
    }

    ToggleSurface(
        checked = showDialog,
        onClick = {
            rows = emptyList()
            errorText = null
            showDialog = true
            refreshQuota()
        },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.Time02,
                    contentDescription = stringResource(R.string.subscription_quota_button),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(
                    stringResource(
                        if (isGeminiSubscription) R.string.gemini_oauth_quota_title
                        else R.string.chatgpt_subscription_quota_title,
                    )
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        loading -> {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.gemini_oauth_quota_loading))
                        }

                        errorText != null -> Text(errorText.orEmpty())
                        rows.isEmpty() -> Text(
                            stringResource(
                                if (isGeminiSubscription) R.string.gemini_oauth_quota_unavailable
                                else R.string.chatgpt_subscription_quota_unavailable,
                            )
                        )

                        else -> rows.forEach { row ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.title)
                                row.remainingFraction?.let { fraction ->
                                    LinearProgressIndicator(
                                        progress = { fraction.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        stringResource(
                                            R.string.gemini_oauth_quota_remaining,
                                            (fraction.coerceIn(0f, 1f) * 100).roundToInt(),
                                        )
                                    )
                                } ?: Text(stringResource(R.string.gemini_oauth_quota_value_unavailable))
                                row.resetTime?.let { resetTime ->
                                    Text(stringResource(R.string.gemini_oauth_quota_reset_time, resetTime))
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = ::refreshQuota, enabled = !loading) {
                    Text(stringResource(R.string.gemini_oauth_quota_refresh))
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.gemini_oauth_quota_close))
                }
            },
        )
    }
}
