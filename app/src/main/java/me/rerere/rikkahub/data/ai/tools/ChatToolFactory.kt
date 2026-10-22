package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.buildMcpTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.voice.CHAT_VOICE_REPLY_TOOL_NAME
import kotlin.uuid.Uuid

class ChatToolFactory(
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
) {
    fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        localToolOptions: List<LocalToolOption>,
        usageLockEnabled: Boolean,
        voiceCallConfigured: Boolean,
        momentAssistantId: Uuid?,
        anonymousQuestionScopeId: Uuid?,
        includeBuildTools: Boolean,
        buildToolAssistantId: Uuid?,
        enabledSkills: Set<String>,
        ttsVoiceDirectorGuidance: String? = null,
        voiceCallActive: Boolean = false,
        legacyToolNames: Set<String> = emptySet(),
    ): List<Tool> = buildList {
        if (settings.enableWebSearch && BuiltInTools.Search !in model.tools) {
            addAll(createSearchTools(settings))
        }
        val configuredLocalTools = localTools.getTools(
                options = localToolOptions,
                usageLockEnabled = usageLockEnabled,
                voiceCallConfigured = voiceCallConfigured,
                momentAssistantId = momentAssistantId,
                anonymousQuestionScopeId = anonymousQuestionScopeId,
                includeBuildTools = includeBuildTools,
                buildToolAssistantId = buildToolAssistantId,
                ttsVoiceDirectorGuidance = ttsVoiceDirectorGuidance,
            )
        val allowedLocalNames = if (voiceCallActive) {
            localTools.getTools(
                options = localToolOptions.filterNot { it == LocalToolOption.Tts || it == LocalToolOption.VoiceCall },
                usageLockEnabled = usageLockEnabled,
            ).mapTo(mutableSetOf()) { it.name }
        } else configuredLocalTools.mapTo(mutableSetOf()) { it.name }
        // Keep unrelated definitions stable for prompt caching; call controls have no valid in-call use.
        addAll(configuredLocalTools.filterNot { tool ->
            voiceCallActive && (tool.name == CHAT_VOICE_REPLY_TOOL_NAME || tool.name == REQUEST_VOICE_CALL_TOOL_NAME)
        }.map { tool ->
            if (tool.name !in allowedLocalNames) tool.copy(
                needsApproval = false,
                execute = { error("Tool unavailable during an active voice call") },
            ) else tool
        })
        if (enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = enabledSkills,
                    allSkills = skillManager.listSkills(),
                    skillManager = skillManager,
                )
            )
        }
        addAll(buildMcpTools(mcpManager.getAllAvailableTools(settings, assistant), legacyToolNames) { serverId, name, args ->
            mcpManager.callTool(serverId, name, args)
        })
    }
}
