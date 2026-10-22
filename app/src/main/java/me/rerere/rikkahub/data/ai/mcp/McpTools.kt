package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import java.security.MessageDigest
import kotlin.uuid.Uuid

internal fun availableMcpTools(settings: Settings, assistant: Assistant): List<Pair<Uuid, McpTool>> =
    settings.mcpServers.filter { it.commonOptions.enable && it.id in assistant.mcpServers }
        .flatMap { server ->
            server.commonOptions.tools.filter { it.enable }.map { server.id to it }
        }

fun mcpToolName(serverId: Uuid, toolName: String): String {
    val readableName = toolName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(25)
    val identity = MessageDigest.getInstance("SHA-256")
        .digest("$serverId\u0000$toolName".toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(it) }
    return "mcp__${readableName}__$identity"
}

fun buildMcpTools(
    availableTools: List<Pair<Uuid, McpTool>>,
    legacyToolNames: Set<String> = emptySet(),
    execute: suspend (Uuid, String, JsonObject) -> List<UIMessagePart>,
): List<Tool> = buildList {
    val definitions = availableTools.distinctBy { (serverId, tool) -> serverId to tool.name }
    val currentTools = definitions.map { (serverId, tool) ->
        Tool(
            name = mcpToolName(serverId, tool.name),
            description = tool.description.orEmpty(),
            parameters = { tool.inputSchema },
            needsApproval = tool.needsApproval,
            execute = { execute(serverId, tool.name, it.jsonObject) },
        )
    }
    addAll(currentTools)

    // Persisted approvals have old names. Resume only unambiguous aliases without rewriting signed history.
    legacyToolNames.filter { it.startsWith("mcp__") && currentTools.none { tool -> tool.name == it } }
        .forEach { legacyName ->
            val matches = definitions.withIndex().filter { (_, definition) ->
                "mcp__${definition.second.name}" == legacyName
            }
            when (matches.size) {
                1 -> add(currentTools[matches.single().index].copy(name = legacyName))
                in 2..Int.MAX_VALUE -> add(Tool(
                    name = legacyName,
                    description = "An ambiguous legacy MCP call; it cannot be executed safely.",
                    execute = { error("Legacy MCP tool $legacyName matches multiple servers; request the tool again.") },
                ))
            }
        }
}
