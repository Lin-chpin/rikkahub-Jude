package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.gemini.GeminiOAuthAccountRepository
import me.rerere.rikkahub.data.ai.gemini.GeminiOAuthLoginService
import me.rerere.rikkahub.data.ai.gemini.GeminiOAuthLoginState
import me.rerere.rikkahub.data.ai.gemini.GeminiOAuthQuotaGroup
import me.rerere.rikkahub.ui.components.ai.formatGeminiQuotaRowTitle
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.formatQuotaResetTimeInShanghai
import org.koin.compose.koinInject

@Composable
internal fun GeminiSubscriptionConfigure() {
    val accountRepository = koinInject<GeminiOAuthAccountRepository>()
    val loginService = koinInject<GeminiOAuthLoginService>()
    val account by accountRepository.account.collectAsState()
    val currentAccount = account
    val loginState by loginService.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var showQuotaDialog by remember { mutableStateOf(false) }
    var quotaLoading by remember { mutableStateOf(false) }
    var quotaGroups by remember { mutableStateOf<List<GeminiOAuthQuotaGroup>>(emptyList()) }
    var quotaError by remember { mutableStateOf<String?>(null) }

    fun refreshQuota() {
        if (quotaLoading) return
        scope.launch {
            quotaLoading = true
            quotaError = null
            runCatching { accountRepository.fetchQuota() }
                .onSuccess { quotaGroups = it }
                .onFailure { quotaError = it.message ?: context.getString(R.string.gemini_oauth_quota_failed) }
            quotaLoading = false
        }
    }

    LaunchedEffect(loginState) {
        when (val state = loginState) {
            GeminiOAuthLoginState.Success -> {
                toaster.show(
                    context.getString(R.string.gemini_oauth_login_success),
                    type = ToastType.Success,
                )
                loginService.consumeState()
            }

            is GeminiOAuthLoginState.Error -> {
                toaster.show(state.message, type = ToastType.Error)
                loginService.consumeState()
            }

            else -> Unit
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.gemini_oauth_description))

        if (currentAccount == null) {
            Text(stringResource(R.string.gemini_oauth_not_signed_in))
        } else {
            val signedInAccount = currentAccount
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (signedInAccount.needsReauthentication) {
                        stringResource(R.string.gemini_oauth_reauthentication_required, signedInAccount.email)
                    } else {
                        stringResource(R.string.gemini_oauth_signed_in, signedInAccount.email)
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        loginService.cancelLogin()
                        scope.launch { accountRepository.signOut() }
                    }
                ) {
                    Text(stringResource(R.string.gemini_oauth_sign_out))
                }
            }
            OutlinedButton(
                onClick = {
                    quotaGroups = emptyList()
                    quotaError = null
                    showQuotaDialog = true
                    refreshQuota()
                },
                enabled = !signedInAccount.needsReauthentication,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.gemini_oauth_view_quota))
            }
        }

        when (loginState) {
            GeminiOAuthLoginState.Waiting -> Text(stringResource(R.string.gemini_oauth_waiting))
            GeminiOAuthLoginState.Completing -> Text(stringResource(R.string.gemini_oauth_completing))
            else -> OutlinedButton(
                onClick = { loginService.startLogin() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (currentAccount == null || currentAccount.needsReauthentication) {
                            R.string.gemini_oauth_login
                        } else {
                            R.string.gemini_oauth_relogin
                        }
                    )
                )
            }
        }
    }

    if (showQuotaDialog) {
        AlertDialog(
            onDismissRequest = { showQuotaDialog = false },
            title = { Text(stringResource(R.string.gemini_oauth_quota_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        quotaLoading -> {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.gemini_oauth_quota_loading))
                        }
                        quotaError != null -> Text(quotaError.orEmpty())
                        quotaGroups.isEmpty() -> Text(stringResource(R.string.gemini_oauth_quota_unavailable))
                        else -> quotaGroups.forEach { group ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(group.displayName)
                                group.buckets.forEach { bucket ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(formatGeminiQuotaRowTitle("", bucket))
                                        bucket.remainingFraction?.let { fraction ->
                                            val percent = (fraction.coerceIn(0.0, 1.0) * 100).roundToInt()
                                            LinearProgressIndicator(
                                                progress = { fraction.toFloat().coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Text(
                                                stringResource(
                                                    R.string.gemini_oauth_quota_remaining,
                                                    percent,
                                                )
                                            )
                                        } ?: Text(stringResource(R.string.gemini_oauth_quota_value_unavailable))
                                        bucket.resetTime?.let { resetTime ->
                                            Text(
                                                stringResource(
                                                    R.string.gemini_oauth_quota_reset_time,
                                                    formatQuotaResetTimeInShanghai(resetTime),
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = ::refreshQuota, enabled = !quotaLoading) {
                    Text(stringResource(R.string.gemini_oauth_quota_refresh))
                }
            },
            confirmButton = {
                TextButton(onClick = { showQuotaDialog = false }) {
                    Text(stringResource(R.string.gemini_oauth_quota_close))
                }
            },
        )
    }
}
