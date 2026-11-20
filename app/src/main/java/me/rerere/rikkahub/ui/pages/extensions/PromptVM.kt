package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookFolder
import me.rerere.rikkahub.data.model.PromptInjection

class PromptVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateModeInjections(injections: List<PromptInjection.ModeInjection>) {
        viewModelScope.launch {
            settingsStore.update { it.copy(modeInjections = injections) }
        }
    }

    fun updateLorebooks(lorebooks: List<Lorebook>, folders: List<LorebookFolder>) {
        viewModelScope.launch {
            settingsStore.update { it.copy(lorebooks = lorebooks, lorebookFolders = folders) }
        }
    }
}
