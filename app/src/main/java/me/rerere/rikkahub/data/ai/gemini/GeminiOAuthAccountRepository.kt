package me.rerere.rikkahub.data.ai.gemini

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.providers.GeminiOAuthCredentials
import me.rerere.ai.provider.providers.GeminiOAuthTokenProvider
import me.rerere.ai.provider.providers.GEMINI_CODE_ASSIST_ENDPOINT
import me.rerere.ai.provider.providers.GEMINI_DAILY_CODE_ASSIST_ENDPOINT
import me.rerere.ai.provider.providers.geminiClientMetadata
import me.rerere.ai.provider.providers.withGeminiAntigravityHeaders
import me.rerere.common.http.await
import me.rerere.common.http.suppressContentLogs
import me.rerere.common.http.withoutAutomaticReplay
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GeminiOAuthAccountRepository(
    context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) : GeminiOAuthTokenProvider {
    private val store = GeminiOAuthCredentialStore(context, json)
    private val mutex = Mutex()
    private var storedAccount = store.read()
    private val _account = MutableStateFlow(storedAccount?.toSummary())
    val account: StateFlow<GeminiOAuthAccountSummary?> = _account.asStateFlow()

    suspend fun completeLogin(tokenResponseBody: String): GeminiOAuthAccountSummary {
        val token = json.parseToJsonElement(tokenResponseBody).jsonObject
        val accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Google did not return an access token")
        val refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Google did not return offline access; sign in again")
        val identity = fetchEmail(accessToken)
        val projectId = discoverProject(accessToken)
        val expiresIn = token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
        val account = GeminiStoredAccount(
            email = identity,
            projectId = projectId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
        )
        return mutex.withLock {
            saveAccount(account)
            account.toSummary()
        }
    }

    suspend fun exchangeAuthorizationCode(
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): GeminiOAuthAccountSummary = withContext(Dispatchers.IO) {
        val response = client.newCall(
            Request.Builder()
                .url(GeminiOAuthClientConfig.TOKEN_URL)
                .post(
                    FormBody.Builder()
                        .add("client_id", GeminiOAuthClientConfig.CLIENT_ID)
                        .add("client_secret", GeminiOAuthClientConfig.CLIENT_SECRET)
                        .add("code", code)
                        .add("code_verifier", codeVerifier)
                        .add("grant_type", "authorization_code")
                        .add("redirect_uri", redirectUri)
                        .build()
                        .withoutAutomaticReplay()
                )
                .suppressContentLogs(true)
                .build()
        ).await()
        val tokenBody = response.use {
            if (!it.isSuccessful) throw IOException("Google sign-in failed: HTTP ${it.code}")
            it.body.string()
        }
        completeLogin(tokenBody)
    }

    suspend fun signOut() = mutex.withLock {
        store.clear()
        storedAccount = null
        _account.value = null
    }

    internal suspend fun fetchQuota(): List<GeminiOAuthQuotaGroup> = withContext(Dispatchers.IO) {
        val credentials = getCredentials()
        val projectBody = buildJsonObject { put("project", credentials.projectId) }
        val summary = postQuotaEndpoint(
            path = "v1internal:retrieveUserQuotaSummary",
            credentials = credentials,
            body = projectBody,
        ).use { response ->
            if (!response.isSuccessful) {
                throw IOException("Google subscription quota request failed: HTTP ${response.code}")
            }
            json.parseToJsonElement(response.body.string()).jsonObject
        }
        val quotaGroups = parseGeminiOAuthQuota(summary)
        if (quotaGroups.isNotEmpty()) return@withContext quotaGroups

        val modelCatalog = postQuotaEndpoint(
            path = "v1internal:fetchAvailableModels",
            credentials = credentials,
            body = projectBody,
        ).use { response ->
            if (!response.isSuccessful) {
                throw IOException("Google subscription quota request failed: HTTP ${response.code}")
            }
            json.parseToJsonElement(response.body.string()).jsonObject
        }
        parseGeminiOAuthQuota(summary, modelCatalog)
    }

    override suspend fun getCredentials(): GeminiOAuthCredentials = mutex.withLock {
        val account = storedAccount ?: error("Sign in with Google to use the Gemini subscription")
        if (account.tokenStatus == GeminiOAuthTokenStatus.INVALID) {
            error("Google sign-in has expired; sign in again")
        }
        val fresh = if (account.expiresAt > System.currentTimeMillis() + REFRESH_MARGIN_MS) {
            account
        } else {
            refresh(account)
        }
        GeminiOAuthCredentials(accessToken = fresh.accessToken, projectId = fresh.projectId)
    }

    override suspend fun markUnauthorized(accessToken: String) {
        mutex.withLock {
            storedAccount?.invalidateIfAccessTokenMatches(accessToken)?.let(::saveAccount)
        }
    }

    private suspend fun refresh(account: GeminiStoredAccount): GeminiStoredAccount = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(GeminiOAuthClientConfig.TOKEN_URL)
            .post(
                FormBody.Builder()
                    .add("client_id", GeminiOAuthClientConfig.CLIENT_ID)
                    .add("client_secret", GeminiOAuthClientConfig.CLIENT_SECRET)
                    .add("refresh_token", account.refreshToken)
                    .add("grant_type", "refresh_token")
                    .build()
                    .withoutAutomaticReplay()
            )
            .suppressContentLogs(true)
            .build()
        val response = client.newCall(request).await()
        response.use {
            val body = it.body.string()
            if (!it.isSuccessful) {
                if (it.code == 401 || isInvalidGrant(body)) {
                    storedAccount?.takeIf { current -> current.refreshToken == account.refreshToken }
                        ?.let { current -> saveAccount(current.copy(tokenStatus = GeminiOAuthTokenStatus.INVALID)) }
                }
                throw IOException("Google token refresh failed: HTTP ${it.code}")
            }
            val token = json.parseToJsonElement(body).jsonObject
            val accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Google did not return a refreshed access token")
            val updated = account.copy(
                accessToken = accessToken,
                refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull ?: account.refreshToken,
                expiresAt = System.currentTimeMillis() +
                    (token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L) * 1000,
                tokenStatus = GeminiOAuthTokenStatus.AVAILABLE,
            )
            saveAccount(updated)
            updated
        }
    }

    private suspend fun fetchEmail(accessToken: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(USERINFO_URL)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .suppressContentLogs(true)
            .build()
        val response = runCatching { client.newCall(request).await() }.getOrNull()
            ?: return@withContext ""
        response.use {
            if (!it.isSuccessful) return@withContext ""
            runCatching {
                json.parseToJsonElement(it.body.string()).jsonObject["email"]
                    ?.jsonPrimitive?.contentOrNull.orEmpty()
            }.getOrDefault("")
        }
    }

    private suspend fun discoverProject(accessToken: String): String = withContext(Dispatchers.IO) {
        val loadBody = buildJsonObject { put("metadata", geminiClientMetadata()) }
        val loadResponse = postCodeAssist("v1internal:loadCodeAssist", accessToken, loadBody)
        val load = loadResponse.use { response ->
            if (!response.isSuccessful) throw IOException("Google account setup failed: HTTP ${response.code}")
            json.parseToJsonElement(response.body.string()).jsonObject
        }
        readProjectId(load["cloudaicompanionProject"])?.let { return@withContext it }

        val currentTier = load["currentTier"]?.jsonObject
        val defaultTier = (load["allowedTiers"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it["isDefault"]?.jsonPrimitive?.booleanOrNull == true }
        val tierId = (currentTier ?: defaultTier)?.get("id")?.jsonPrimitive?.contentOrNull
            ?: LEGACY_TIER_ID
        var lastOperation: JsonObject? = null
        repeat(ONBOARD_MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(ONBOARD_RETRY_DELAY_MS)
            val body = buildJsonObject {
                put("tierId", tierId)
                put("metadata", geminiClientMetadata())
            }
            val response = postCodeAssist("v1internal:onboardUser", accessToken, body)
            val operation = response.use {
                if (!it.isSuccessful) throw IOException("Google account setup failed: HTTP ${it.code}")
                json.parseToJsonElement(it.body.string()).jsonObject
            }
            lastOperation = operation
            readProjectId(operation["response"]?.jsonObject?.get("cloudaicompanionProject"))
                ?.let { return@withContext it }
            if (operation["done"]?.jsonPrimitive?.booleanOrNull == true) {
                error("Google account setup did not return a project")
            }
        }
        error(if (lastOperation == null) "Google account setup did not complete" else "Google account setup is still pending")
    }

    private suspend fun postCodeAssist(
        path: String,
        accessToken: String,
        body: JsonObject,
    ) = client.newCall(
        Request.Builder()
            .url("$GEMINI_CODE_ASSIST_ENDPOINT/$path")
            .withGeminiAntigravityHeaders(accessToken)
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE).withoutAutomaticReplay())
            .suppressContentLogs(true)
            .build()
    ).await()

    private suspend fun postQuotaEndpoint(
        path: String,
        credentials: GeminiOAuthCredentials,
        body: JsonObject,
    ) = client.newCall(
        Request.Builder()
            .url("$GEMINI_DAILY_CODE_ASSIST_ENDPOINT/$path")
            .withGeminiAntigravityHeaders(credentials.accessToken)
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .suppressContentLogs(true)
            .build()
    ).await().also { response ->
        if (response.code == 401) markUnauthorized(credentials.accessToken)
    }

    private fun saveAccount(account: GeminiStoredAccount) {
        store.write(account)
        storedAccount = account
        _account.value = account.toSummary()
    }

    private fun readProjectId(value: kotlinx.serialization.json.JsonElement?): String? =
        ((value as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            ?: (value as? JsonPrimitive)?.contentOrNull)
            ?.takeIf { it.isNotBlank() }

    private fun isInvalidGrant(body: String): Boolean = runCatching {
        val error = json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        error == "invalid_grant" || error == "invalid_token"
    }.getOrDefault(false)

    private companion object {
        const val USERINFO_URL = "https://www.googleapis.com/oauth2/v1/userinfo?alt=json"
        const val REFRESH_MARGIN_MS = 30_000L
        const val LEGACY_TIER_ID = "legacy-tier"
        const val ONBOARD_MAX_ATTEMPTS = 5
        const val ONBOARD_RETRY_DELAY_MS = 2_000L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
