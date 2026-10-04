package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import kotlin.uuid.Uuid

class ChatToolFactory(
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
) {
    fun createTools(
        settings: Settings,
        model: Model,
        localToolOptions: List<LocalToolOption>,
        usageLockEnabled: Boolean,
        voiceCallConfigured: Boolean,
        momentAssistantId: Uuid?,
        anonymousQuestionScopeId: Uuid?,
        includeBuildTools: Boolean,
        buildToolAssistantId: Uuid?,
        enabledSkills: Set<String>,
    ): List<Tool> = buildList {
        if (settings.enableWebSearch && BuiltInTools.Search !in model.tools) {
            addAll(createSearchTools(settings))
        }
        addAll(
            localTools.getTools(
                options = localToolOptions,
                usageLockEnabled = usageLockEnabled,
                voiceCallConfigured = voiceCallConfigured,
                momentAssistantId = momentAssistantId,
                anonymousQuestionScopeId = anonymousQuestionScopeId,
                includeBuildTools = includeBuildTools,
                buildToolAssistantId = buildToolAssistantId,
            )
        )
        if (enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = enabledSkills,
                    allSkills = skillManager.listSkills(),
                    skillManager = skillManager,
                )
            )
        }
        mcpManager.getAllAvailableTools().forEach { (serverId, tool) ->
            add(
                Tool(
                    name = "mcp__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = tool.needsApproval,
                    execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                )
            )
        }
    }
}
