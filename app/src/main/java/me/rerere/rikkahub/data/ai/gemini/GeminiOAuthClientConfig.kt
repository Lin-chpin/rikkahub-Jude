package me.rerere.rikkahub.data.ai.gemini

internal object GeminiOAuthClientConfig {
    // Google's installed-app client is distributed publicly; this protocol requires its client secret in the app.
    const val CLIENT_ID =
        "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
    const val CLIENT_SECRET = "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"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val SCOPES = "https://www.googleapis.com/auth/cloud-platform " +
        "https://www.googleapis.com/auth/userinfo.email " +
        "https://www.googleapis.com/auth/userinfo.profile " +
        "https://www.googleapis.com/auth/cclog " +
        "https://www.googleapis.com/auth/experimentsandconfigs"
    const val CALLBACK_PORT = 51121
    const val CALLBACK_PATH = "/oauth-callback"
    const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT$CALLBACK_PATH"
}
