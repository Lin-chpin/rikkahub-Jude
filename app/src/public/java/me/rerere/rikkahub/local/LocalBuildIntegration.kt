package me.rerere.rikkahub.local

import android.app.Application
import android.content.Context
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.ui.components.ui.CardGroupScope

/**
 * Public distribution boundary.
 *
 * Local-only features implement the same narrow hooks in another source set. The public
 * variant intentionally keeps these hooks inert and therefore packages no local feature code.
 */
object LocalBuildIntegration {
    fun onApplicationCreated(application: Application) = Unit

    fun onUserMessageSent(context: Context, message: UIMessage) = Unit
}

fun CardGroupScope.addLocalSettingsExtension(context: Context) = Unit
