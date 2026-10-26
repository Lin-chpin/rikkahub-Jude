package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.referentialEqualityPolicy
import me.rerere.rikkahub.data.datastore.Settings

// Settings has large nested values; track only readers and compare the root by identity.
val LocalSettings = compositionLocalOf<Settings>(referentialEqualityPolicy()) {
    error("No SettingsStore provided")
}
