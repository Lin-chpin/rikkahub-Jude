package me.rerere.rikkahub.data.ai.gemini

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.BufferedReader
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class GeminiOAuthLoginService(
    context: Context,
    private val accountRepository: GeminiOAuthAccountRepository,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<GeminiOAuthLoginState>(GeminiOAuthLoginState.Idle)
    val state: StateFlow<GeminiOAuthLoginState> = _state.asStateFlow()
    @Volatile private var loginJob: Job? = null
    @Volatile private var callbackServer: ServerSocket? = null

    @Synchronized
    fun startLogin() {
        loginJob?.cancel()
        closeCallbackServer()
        loginJob = scope.launch {
            var server: ServerSocket? = null
            try {
                val stateToken = randomState()
                val codeVerifier = randomState()
                server = openCallbackServer()
                callbackServer = server
                _state.value = GeminiOAuthLoginState.Waiting
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, authorizationUri(stateToken, codeChallenge(codeVerifier))).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                val code = awaitAuthorizationCode(server, stateToken)
                _state.value = GeminiOAuthLoginState.Completing
                accountRepository.exchangeAuthorizationCode(
                    code = code,
                    redirectUri = GeminiOAuthClientConfig.REDIRECT_URI,
                    codeVerifier = codeVerifier,
                )
                _state.value = GeminiOAuthLoginState.Success
            } catch (error: CancellationException) {
                throw error
            } catch (error: GoogleAuthorizationException) {
                _state.value = GeminiOAuthLoginState.Error(
                    context.getString(R.string.gemini_oauth_authorization_denied)
                )
            } catch (error: BindException) {
                _state.value = GeminiOAuthLoginState.Error(
                    context.getString(R.string.gemini_oauth_callback_port_unavailable)
                )
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "Google subscription sign-in failed (${error::class.java.simpleName})")
                _state.value = GeminiOAuthLoginState.Error(
                    context.getString(R.string.gemini_oauth_login_failed)
                )
            } finally {
                runCatching { server?.close() }
                if (callbackServer === server) callbackServer = null
            }
        }
    }

    fun consumeState() {
        _state.value = GeminiOAuthLoginState.Idle
    }

    @Synchronized
    fun cancelLogin() {
        loginJob?.cancel()
        loginJob = null
        closeCallbackServer()
        _state.value = GeminiOAuthLoginState.Idle
    }

    private fun openCallbackServer(): ServerSocket = ServerSocket().apply {
        reuseAddress = false
        bind(
            InetSocketAddress(
                InetAddress.getByName("127.0.0.1"),
                GeminiOAuthClientConfig.CALLBACK_PORT,
            )
        )
        soTimeout = CALLBACK_TIMEOUT_MS
    }

    private suspend fun awaitAuthorizationCode(server: ServerSocket, expectedState: String): String =
        withContext(Dispatchers.IO) {
            while (true) {
                val socket = server.accept()
                socket.use { client ->
                    client.soTimeout = SOCKET_TIMEOUT_MS
                    val requestTarget = readRequestTarget(client.getInputStream().bufferedReader())
                    val callback = requestTarget?.let { target ->
                        "http://localhost:${GeminiOAuthClientConfig.CALLBACK_PORT}$target".toHttpUrlOrNull()
                    }
                    if (callback == null || callback.encodedPath != GeminiOAuthClientConfig.CALLBACK_PATH) {
                        writeResponse(client, status = 404, message = "Sign-in callback not found.")
                        return@use
                    }

                    val actualState = callback.queryParameter("state")
                    if (actualState == null || !MessageDigest.isEqual(
                            expectedState.toByteArray(),
                            actualState.toByteArray(),
                        )
                    ) {
                        writeResponse(client, status = 400, message = "Sign-in state did not match.")
                        return@use
                    }

                    val error = callback.queryParameter("error")
                    if (!error.isNullOrBlank()) {
                        writeResponse(client, status = 400, message = "Google sign-in was not completed.")
                        throw GoogleAuthorizationException()
                    }

                    val code = callback.queryParameter("code")
                    if (code.isNullOrBlank()) {
                        writeResponse(client, status = 400, message = "Google did not return a sign-in code.")
                        throw GoogleAuthorizationException()
                    }

                    writeResponse(client, status = 200, message = "Google sign-in received. Return to RikkaHub.")
                    return@withContext code
                }
            }
            error("Unreachable sign-in callback state")
        }

    private fun readRequestTarget(reader: BufferedReader): String? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2 || parts[0] != "GET") return null
        repeat(MAX_HEADER_LINES) {
            val line = reader.readLine() ?: return null
            if (line.isEmpty()) return parts[1]
        }
        return null
    }

    private fun writeResponse(socket: Socket, status: Int, message: String) {
        val body = "<!doctype html><meta charset=\"utf-8\"><title>RikkaHub</title><p>$message</p>"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val response = buildString {
            val reason = when (status) {
                200 -> "OK"
                404 -> "Not Found"
                else -> "Bad Request"
            }
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().apply {
            write(response)
            write(bytes)
            flush()
        }
    }

    private fun authorizationUri(stateToken: String, challenge: String): Uri =
        Uri.parse(GeminiOAuthClientConfig.AUTHORIZE_URL)
            .buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", GeminiOAuthClientConfig.CLIENT_ID)
            .appendQueryParameter("redirect_uri", GeminiOAuthClientConfig.REDIRECT_URI)
            .appendQueryParameter("scope", GeminiOAuthClientConfig.SCOPES)
            .appendQueryParameter("state", stateToken)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent select_account")
            .build()

    private fun randomState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun codeChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    )

    @Synchronized
    private fun closeCallbackServer() {
        runCatching { callbackServer?.close() }
        callbackServer = null
    }

    private class GoogleAuthorizationException : IOException()

    private companion object {
        const val TAG = "GeminiOAuthLoginService"
        const val CALLBACK_TIMEOUT_MS = 5 * 60 * 1000
        const val SOCKET_TIMEOUT_MS = 5_000
        const val MAX_HEADER_LINES = 32
    }
}

sealed interface GeminiOAuthLoginState {
    data object Idle : GeminiOAuthLoginState
    data object Waiting : GeminiOAuthLoginState
    data object Completing : GeminiOAuthLoginState
    data object Success : GeminiOAuthLoginState
    data class Error(val message: String) : GeminiOAuthLoginState
}
