package me.rerere.rikkahub.ui.pages.backup

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.RestoredFile
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.transfer.RikkaHubTransferImportReport
import me.rerere.rikkahub.data.sync.transfer.RikkaHubTransferImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        webDavSync.backup(settings.value.webDavConfig)
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File {
        val file = webDavSync.prepareBackupFile(settings.value.webDavConfig.copy())
        recordBackupTime()
        return file
    }

    suspend fun restoreFromLocalFile(file: File) {
        webDavSync.restoreFromLocalFile(file, settings.value.webDavConfig)
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult {
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = settings.value.assistantId,
            providers = settings.value.providers,
            onConversation = { conversation ->
                if (conversationRepository.existsConversationById(conversation.id)) {
                    skippedExistingConversations++
                } else {
                    conversationRepository.insertConversation(conversation)
                    importedConversations++
                }
            }
        )

        val targetAssistantId = settings.value.assistantId
        settingsStore.update(
            settings.value.copy(
                providers = result.providers + settings.value.providers,
                assistants = settings.value.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        )

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "drop ${result.skippedImageParts} images"
        )
        return ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
        )
    }

    suspend fun restoreFromRikkaHubTransfer(file: File): RikkaHubTransferRestoreResult {
        val result = RikkaHubTransferImporter.import(
            file = file,
            assistantId = settings.value.assistantId,
        )
        require(result.conversations.isNotEmpty()) {
            "转换包没有可导入的聊天记录（适配器报告源聊天 ${result.manifest.conversationCount} 个）。请查看 diagnostics.json。"
        }
        val importErrors = result.errors.toMutableList()
        val attachmentUris = mutableMapOf<String, String>()
        val importedSettings = result.settingsJson
            ?.takeIf { result.isCompleteRestore }
            ?.let { settingsJson ->
            val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
            val normalizedJson = SettingsJsonMigrator.migrateLocalFileUris(
                settingsJson = migratedJson,
                filesDir = filesManager.appFilesDir,
            )
            JsonInstant.decodeFromString<Settings>(normalizedJson).also {
                require(it.assistants.isNotEmpty()) { "转换包中的助手列表为空" }
            }
        }
        val isFullRestore = importedSettings != null
        if (isFullRestore) {
            require(result.conversations.isNotEmpty()) {
                "完整恢复包不包含可导入聊天记录，未修改当前数据"
            }
        }

        val restoredFileUris = if (isFullRestore) {
            filesManager.replaceManagedFiles(
                result.files.map { file ->
                    RestoredFile(
                        relativePath = file.descriptor.relativePath,
                        displayName = file.descriptor.displayName,
                        mimeType = file.descriptor.mimeType,
                        bytes = file.bytes,
                    )
                }
            )
        } else {
            emptyMap()
        }

        val importedConversationCount = if (importedSettings != null) {
            val materializedConversations = result.conversations.map { conversation ->
                materializeTransferAttachments(
                    conversation = conversation,
                    attachments = result.attachments,
                    attachmentUris = attachmentUris,
                    restoredFileUris = restoredFileUris,
                    errors = importErrors,
                )
            }
            val conversations = remapMissingAssistants(
                conversations = materializedConversations,
                importedSettings = importedSettings,
            )
            conversationRepository.replaceAllConversations(conversations)
            settingsStore.update(importedSettings)
            conversations.size
        } else {
            var importedCount = 0
            result.conversations.forEach { conversation ->
                if (!conversationRepository.existsConversationById(conversation.id)) {
                    val materializedConversation = materializeTransferAttachments(
                        conversation = conversation,
                        attachments = result.attachments,
                        attachmentUris = attachmentUris,
                        restoredFileUris = restoredFileUris,
                        errors = importErrors,
                    )
                    conversationRepository.insertConversation(materializedConversation)
                    importedCount++
                }
            }
            if (result.conversations.any { !it.customSystemPrompt.isNullOrBlank() }) {
                val targetAssistantId = settings.value.assistantId
                settingsStore.update(
                    settings.value.copy(
                        assistants = settings.value.assistants.map { assistant ->
                            if (assistant.id == targetAssistantId) {
                                assistant.copy(allowConversationSystemPrompt = true)
                            } else {
                                assistant
                            }
                        }
                    )
                )
            }
            importedCount
        }

        val skippedExistingConversations = if (importedSettings == null) {
            result.conversations.size - importedConversationCount
        } else {
            0
        }

        val report = RikkaHubTransferImportReport(
            parsedConversations = result.conversations.size + result.skippedConversations,
            importedConversations = importedConversationCount,
            skippedExistingConversations = skippedExistingConversations,
            skippedConversations = result.skippedConversations,
            skippedNodes = result.skippedNodes,
            parsedMessages = result.parsedMessages,
            warnings = result.warnings,
            errors = importErrors,
            replacedAllData = isFullRestore,
        )
        Log.i(TAG, "restoreFromRikkaHubTransfer: ${report.toDiagnosticText()}")
        return RikkaHubTransferRestoreResult(report)
    }

    private suspend fun materializeTransferAttachments(
        conversation: Conversation,
        attachments: Map<String, me.rerere.rikkahub.data.sync.transfer.RikkaHubTransferAttachmentData>,
        attachmentUris: MutableMap<String, String>,
        restoredFileUris: Map<String, String>,
        errors: MutableList<String>,
    ): Conversation {
        attachments.forEach { (id, attachment) ->
            if (id in attachmentUris) return@forEach
            runCatching {
                val restoredUri = attachment.descriptor.sourceRelativePath
                    ?.let { restoredFileUris[it] }
                if (restoredUri != null) {
                    attachmentUris[id] = restoredUri
                } else {
                    val entity = filesManager.saveUploadFromBytes(
                        bytes = attachment.bytes,
                        displayName = attachment.descriptor.fileName,
                        mimeType = attachment.descriptor.mimeType,
                    )
                    attachmentUris[id] = filesManager.getFile(entity).toUri().toString()
                }
            }.onFailure {
                errors += "attachment:$id:${it.message ?: "save failed"}"
            }
        }

        return conversation.copy(
            messageNodes = conversation.messageNodes.map { node ->
                node.copy(
                    messages = node.messages.map { message ->
                        val rewritten = message.copy(
                            parts = rewriteTransferAttachments(message.parts, attachmentUris)
                        )
                        runCatching {
                            filesManager.convertBase64ImagePartToLocalFile(rewritten)
                        }.onFailure {
                            errors += "message:${message.id}:image:${it.message ?: "save failed"}"
                        }.getOrDefault(rewritten)
                    }
                )
            }
        )
    }

    private fun remapMissingAssistants(
        conversations: List<Conversation>,
        importedSettings: Settings,
    ): List<Conversation> {
        val assistantIds = importedSettings.assistants.map { it.id }.toSet()
        val fallbackAssistantId = importedSettings.assistantId.takeIf { it in assistantIds }
            ?: importedSettings.assistants.first().id
        return conversations.map { conversation ->
            if (conversation.assistantId in assistantIds) {
                conversation
            } else {
                conversation.copy(assistantId = fallbackAssistantId)
            }
        }
    }

    private fun rewriteTransferAttachments(
        parts: List<UIMessagePart>,
        attachmentUris: Map<String, String>,
    ): List<UIMessagePart> = parts.map { part ->
        when (part) {
            is UIMessagePart.Image -> part.copy(url = attachmentUris.resolve(part.url))
            is UIMessagePart.Video -> part.copy(url = attachmentUris.resolve(part.url))
            is UIMessagePart.Audio -> part.copy(url = attachmentUris.resolve(part.url))
            is UIMessagePart.Document -> part.copy(url = attachmentUris.resolve(part.url))
            is UIMessagePart.Tool -> part.copy(
                output = rewriteTransferAttachments(part.output, attachmentUris)
            )
            else -> part
        }
    }

    private fun Map<String, String>.resolve(url: String): String {
        val id = url.removePrefix("rhk://attachment/")
        return get(id) ?: url
    }

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers: $importProviders")

        updateSettings(
            settings.value.copy(
                providers = importProviders + settings.value.providers,
            )
        )
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        s3Sync.restoreFromS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
)

data class RikkaHubTransferRestoreResult(
    val report: RikkaHubTransferImportReport,
)
