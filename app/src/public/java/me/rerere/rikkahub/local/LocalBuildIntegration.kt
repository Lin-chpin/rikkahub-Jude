package me.rerere.rikkahub.local

import android.app.Application
import android.content.Context
import me.rerere.rikkahub.ui.components.ui.CardGroupScope

/**
 * Public distribution boundary.
 *
 * Local-only features implement the same narrow hooks in another source set. The public
 * variant intentionally keeps these hooks inert and therefore packages no local feature code.
 */
object LocalBuildIntegration {
    fun onApplicationCreated(application: Application) = Unit
}

fun CardGroupScope.addLocalSettingsExtension(context: Context) = Unit
