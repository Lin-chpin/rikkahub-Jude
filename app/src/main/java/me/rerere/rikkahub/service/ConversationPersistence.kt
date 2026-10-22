package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.time.Instant
import kotlin.uuid.Uuid

/** Coordinates persisted mutations with the live session; callers supply intent, not old snapshots. */
internal class ConversationPersistence(
    private val repository: ConversationRepository,
    private val filesManager: FilesManager,
    private val sessionFor: (Uuid) -> ConversationSession,
) {
    suspend fun mutateConversation(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
    ): Conversation {
        val session = sessionFor(conversationId)
        return session.withRefSuspend {
            session.withPersistence {
                check(!session.deleted) { "Conversation has been deleted" }
                session.initializeState {
                    repository.getConversationById(conversationId) ?: session.state.value
                }
                val previous = session.state.value
                val updated = session.updateState { update(it).normalizeCompressionState() }
                persistConversationSnapshot(updated)
                checkFilesDelete(session.state.value, previous)
                updated
            }
        }
    }

    suspend fun persistConversation(conversationId: Uuid) {
        val session = sessionFor(conversationId)
        session.withRefSuspend {
            session.withPersistence {
                if (session.deleted) return@withPersistence
                session.initializeState {
                    repository.getConversationById(conversationId) ?: session.state.value
                }
                persistConversationSnapshot(session.state.value)
            }
        }
    }

    suspend fun updateStoredConversation(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val session = sessionFor(conversationId)
        session.withRefSuspend {
            session.withPersistence {
                // An auxiliary request must not recreate a conversation deleted while it was running.
                if (session.deleted || !repository.existsConversationById(conversationId)) return@withPersistence
                session.initializeState { requireNotNull(repository.getConversationById(conversationId)) }
                val updated = session.updateState { update(it).normalizeCompressionState() }
                persistConversationSnapshot(updated)
            }
        }
    }

    suspend fun appendExternalMessage(
        conversationId: Uuid,
        assistant: Assistant,
        message: UIMessage,
        createIfMissing: Boolean = false,
        canAppend: suspend () -> Boolean,
    ): Boolean {
        val session = sessionFor(conversationId)
        return session.withRefSuspend {
            session.withPersistence {
                if (session.deleted) return@withPersistence false
                if (!createIfMissing && !repository.existsConversationById(conversationId)) return@withPersistence false
                session.initializeState {
                    repository.getConversationById(conversationId)
                        ?: Conversation.ofId(conversationId, assistant.id).copy(title = assistant.name.ifBlank { "AI" })
                }
                if (session.isGenerating || !canAppend()) return@withPersistence false
                val updated = session.updateState { current ->
                    if (session.isGenerating || current.assistantId != assistant.id) current else current.copy(
                        messageNodes = current.messageNodes + message.toMessageNode(), updateAt = Instant.now(),
                    )
                }
                if (updated.messageNodes.none { it.messages.any { candidate -> candidate.id == message.id } }) return@withPersistence false
                persistConversationSnapshot(updated)
                true
            }
        }
    }

    suspend fun deleteConversation(conversation: Conversation, deleteFiles: Boolean): Conversation {
        val session = sessionFor(conversation.id)
        return session.withRefSuspend {
            session.withPersistence {
                val snapshot = repository.getConversationById(conversation.id) ?: conversation
                repository.deleteConversation(snapshot, deleteFiles)
                session.markDeleted()
                snapshot
            }
        }
    }

    suspend fun restoreConversation(conversation: Conversation) {
        val session = sessionFor(conversation.id)
        session.withRefSuspend {
            session.withPersistence {
                if (repository.existsConversationById(conversation.id)) return@withPersistence
                repository.insertConversation(conversation)
                session.restoreState(conversation)
            }
        }
    }

    suspend fun finishConversationDeletion(conversation: Conversation) {
        val session = sessionFor(conversation.id)
        session.withRefSuspend {
            session.withPersistence {
                if (!repository.existsConversationById(conversation.id)) {
                    filesManager.deleteChatFiles(conversation.files)
                }
            }
        }
    }

    private suspend fun persistConversationSnapshot(conversation: Conversation) {
        val exists = repository.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) return
        if (exists) repository.updateConversation(conversation)
        else repository.insertConversation(conversation)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        if (newConversation.messageNodes === oldConversation.messageNodes) return
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
        }
    }

    suspend fun persistGeneration(conversationId: Uuid, expectedJob: Job) {
        val session = sessionFor(conversationId)
        session.withRefSuspend {
            session.withPersistence {
                if (!session.deleted && session.getJob() === expectedJob) persistConversationSnapshot(session.state.value)
            }
        }
    }
}
