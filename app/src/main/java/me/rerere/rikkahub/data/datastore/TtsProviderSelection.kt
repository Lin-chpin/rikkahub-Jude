package me.rerere.rikkahub.data.datastore

import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

/**
 * Keeps the selected TTS provider usable after settings imports, migrations, or deletions.
 * A persisted selection may refer to a provider that is no longer present in the list.
 */
data class TtsProviderSelection(
    val providers: List<TTSProviderSetting>,
    val selectedProviderId: Uuid?,
)

fun resolveTtsProviderSelection(
    providers: List<TTSProviderSetting>,
    selectedProviderId: Uuid?,
): TtsProviderSelection {
    val uniqueProviders = providers.distinctBy { it.id }
    val selectedProvider = uniqueProviders.firstOrNull { it.id == selectedProviderId }
        ?: uniqueProviders.firstOrNull()
    return TtsProviderSelection(
        providers = uniqueProviders,
        selectedProviderId = selectedProvider?.id,
    )
}
