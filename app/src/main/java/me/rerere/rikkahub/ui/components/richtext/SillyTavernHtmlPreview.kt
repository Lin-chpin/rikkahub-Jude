package me.rerere.rikkahub.ui.components.richtext

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SillyTavernHtmlPreview(html: String, modifier: Modifier = Modifier) {
    val restrictedHtml = remember(html) { addPreviewCsp(html) }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.apply {
                    javaScriptEnabled = true
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    domStorageEnabled = false
                    databaseEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setGeolocationEnabled(false)
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                webViewClient = object : WebViewClient() {
                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = true
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(420.dp)
            .clip(RoundedCornerShape(8.dp)),
        update = { webView ->
            if (webView.tag != restrictedHtml) {
                webView.tag = restrictedHtml
                webView.loadDataWithBaseURL(
                    "https://rikkahub-preview.invalid/",
                    restrictedHtml,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        },
    )
}

private fun addPreviewCsp(html: String): String {
    val csp = """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline' data:; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; frame-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">"""
    val head = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)
    val document = Regex("""<html\b[^>]*>""", RegexOption.IGNORE_CASE)
    val headMatch = head.find(html)
    if (headMatch != null) return html.replaceRange(headMatch.range, headMatch.value + csp)
    val documentMatch = document.find(html)
    if (documentMatch != null) return html.replaceRange(documentMatch.range, documentMatch.value + "<head>$csp</head>")
    return "<head>$csp</head>$html"
}
