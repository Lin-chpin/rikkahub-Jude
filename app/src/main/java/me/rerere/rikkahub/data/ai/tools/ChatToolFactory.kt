package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.ImageGenerationService
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.buildMcpTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

class ChatToolFactory(
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val imageGenerationService: ImageGenerationService,
    private val filesManager: FilesManager,
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
    ): List<Tool> {
        val imageGenerationTool = createImageGenerationTool(settings, model, imageGenerationService, filesManager)
        val searchTools = if (settings.enableWebSearch && BuiltInTools.Search !in model.tools) {
            createSearchTools(settings)
        } else {
            emptyList()
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
        } else {
            configuredLocalTools.mapTo(mutableSetOf()) { it.name }
        }
        val skillTools = if (enabledSkills.isNotEmpty()) {
            createSkillTools(
                enabledSkills = enabledSkills,
                allSkills = skillManager.listSkills(),
                skillManager = skillManager,
            )
        } else {
            emptyList()
        }
        val mcpTools = buildMcpTools(mcpManager.getAllAvailableTools(settings, assistant), legacyToolNames) { serverId, name, args ->
            mcpManager.callTool(serverId, name, args)
        }
        return assembleChatTools(
            imageGenerationTool = imageGenerationTool,
            searchTools = searchTools,
            configuredLocalTools = configuredLocalTools,
            allowedLocalNames = allowedLocalNames,
            skillTools = skillTools,
            mcpTools = mcpTools,
            voiceCallActive = voiceCallActive,
        )
    }
}

// Keep tool definitions and ordering identical across normal chat and active voice calls for prompt caching;
// disallowed tools remain declared with approval disabled and an execution guard.
internal fun assembleChatTools(
    imageGenerationTool: Tool,
    searchTools: Collection<Tool>,
    configuredLocalTools: List<Tool>,
    allowedLocalNames: Set<String>,
    skillTools: List<Tool>,
    mcpTools: List<Tool>,
    voiceCallActive: Boolean,
): List<Tool> = buildList {
    add(if (voiceCallActive) imageGenerationTool.disabledForActiveVoiceCall() else imageGenerationTool)
    addAll(searchTools)
    addAll(configuredLocalTools.map { tool ->
        if (voiceCallActive && tool.name !in allowedLocalNames) {
            tool.disabledForActiveVoiceCall()
        } else {
            tool
        }
    })
    addAll(skillTools)
    addAll(mcpTools)
}

private fun Tool.disabledForActiveVoiceCall(): Tool = copy(
    needsApproval = false,
    execute = { error("Tool unavailable during an active voice call") },
)
