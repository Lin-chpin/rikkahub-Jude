package me.rerere.rikkahub.ui.components.richtext

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.R

private val HTML_PREVIEW_COLLAPSED_HEIGHT = 220.dp

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SillyTavernHtmlPreview(
    html: String,
    modifier: Modifier = Modifier,
    allowInlineScripts: Boolean = false,
) {
    val restrictedHtml = remember(html, allowInlineScripts) {
        prepareHtmlPreviewDocument(html, allowInlineScripts)
    }
    var contentHeight by remember(html) { mutableStateOf(HTML_PREVIEW_COLLAPSED_HEIGHT) }
    var hasMeasuredContent by remember(html) { mutableStateOf(false) }
    var expanded by remember(html) { mutableStateOf(false) }
    val showExpand = hasMeasuredContent && contentHeight > HTML_PREVIEW_COLLAPSED_HEIGHT
    val visibleHeight = if (!hasMeasuredContent) {
        HTML_PREVIEW_COLLAPSED_HEIGHT
    } else {
        htmlPreviewVisibleHeight(contentHeight, expanded)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    val previewView = this
                    val updateHeight = {
                        previewView.updateContentHeight { measuredHeight ->
                            contentHeight = measuredHeight
                            hasMeasuredContent = true
                        }
                    }
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.apply {
                        javaScriptEnabled = allowInlineScripts
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
                        useWideViewPort = true
                        loadWithOverviewMode = false
                        setSupportZoom(false)
                        builtInZoomControls = false
                    }
                    webViewClient = object : WebViewClient() {
                        @Suppress("DEPRECATION")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = true

                        override fun onPageFinished(view: WebView, url: String?) {
                            updateHeight()
                        }
                    }
                    addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                        if (right - left != oldRight - oldLeft) updateHeight()
                    }
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            postDelayed(Runnable {
                                if (previewView.isAttachedToWindow) updateHeight()
                            }, 180L)
                        }
                        false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(visibleHeight.coerceAtLeast(1.dp))
                .clip(RoundedCornerShape(8.dp)),
            update = { webView ->
                webView.settings.javaScriptEnabled = allowInlineScripts
                if (webView.tag != restrictedHtml) {
                    contentHeight = HTML_PREVIEW_COLLAPSED_HEIGHT
                    hasMeasuredContent = false
                    expanded = false
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
        if (showExpand) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (expanded) R.string.code_block_collapse else R.string.code_block_expand,
                    ),
                )
            }
        }
    }
}

internal fun htmlPreviewVisibleHeight(contentHeight: Dp, expanded: Boolean): Dp {
    val fullHeight = contentHeight.coerceAtLeast(1.dp)
    return if (expanded) fullHeight else fullHeight.coerceAtMost(HTML_PREVIEW_COLLAPSED_HEIGHT)
}

private fun WebView.updateContentHeight(onHeightChanged: (Dp) -> Unit) {
    // With the injected device-width viewport, WebView CSS pixels map to Compose dp.
    if (settings.javaScriptEnabled) {
        evaluateJavascript(
            "Math.max(document.documentElement ? document.documentElement.scrollHeight : 0, " +
                "document.body ? document.body.scrollHeight : 0)",
        ) { result ->
            val cssHeight = result?.trim('"')?.toFloatOrNull()
            if (cssHeight != null && cssHeight.isFinite() && cssHeight > 0f) {
                onHeightChanged(cssHeight.dp)
            }
        }
    } else {
        post {
            val cssHeight = this.contentHeight
            if (cssHeight > 0) onHeightChanged(cssHeight.dp)
        }
    }
}
