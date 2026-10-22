package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.availableMcpTools
import me.rerere.rikkahub.data.ai.mcp.buildMcpTools
import me.rerere.rikkahub.data.ai.mcp.mcpToolName
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class McpToolsTest {
    @Test
    fun `request assistant selects servers independently of the current assistant`() {
        val first = McpServerConfig.StreamableHTTPServer(commonOptions = McpCommonOptions(tools = listOf(McpTool(name = "fetch"))))
        val second = first.copy(id = Uuid.random())
        val assistant = Assistant(mcpServers = setOf(first.id))
        val currentAssistant = Assistant(mcpServers = setOf(second.id))
        val settings = Settings(assistantId = currentAssistant.id, assistants = listOf(assistant, currentAssistant), mcpServers = listOf(first, second))

        assertEquals(listOf(first.id), availableMcpTools(settings, assistant).map { it.first })
        assertEquals(listOf(second.id), availableMcpTools(settings, currentAssistant).map { it.first })
    }

    @Test
    fun `names preserve server identity and do not collide after readable name truncation`() = runBlocking {
        val first = Uuid.random()
        val second = Uuid.random()
        val longName = "a".repeat(70)
        val available = listOf(first to McpTool(name = longName), second to McpTool(name = longName), first to McpTool(name = longName + "b"))
        val calls = mutableListOf<Pair<Uuid, String>>()
        val tools = buildMcpTools(available) { server, name, _ ->
            calls += server to name
            listOf(UIMessagePart.Text("done"))
        }
        assertEquals(3, tools.map { it.name }.distinct().size)
        assertTrue(tools.all { it.name.length <= 64 && it.name.matches(Regex("[A-Za-z0-9_-]+")) })
        assertEquals(tools.first().name, mcpToolName(first, longName))
        tools.forEach { it.execute(JsonObject(emptyMap())) }
        assertEquals(available.map { it.first to it.second.name }, calls)
    }

    @Test
    fun `legacy pending calls resume only an unambiguous server and retain approval`() = runBlocking {
        val first = Uuid.random()
        val definition = McpTool(name = "fetch", needsApproval = true)
        var calledServer: Uuid? = null
        val normal = buildMcpTools(listOf(first to definition)) { _, _, _ -> emptyList() }
        assertFalse(normal.any { it.name == "mcp__fetch" })
        val legacy = buildMcpTools(listOf(first to definition), setOf("mcp__fetch")) { server, _, _ ->
            calledServer = server
            emptyList()
        }.single { it.name == "mcp__fetch" }
        assertTrue(legacy.needsApproval)
        legacy.execute(JsonObject(emptyMap()))
        assertEquals(first, calledServer)

        calledServer = null
        val ambiguous = buildMcpTools(listOf(first to definition, Uuid.random() to definition), setOf("mcp__fetch")) { server, _, _ ->
            calledServer = server
            emptyList()
        }.single { it.name == "mcp__fetch" }
        val result = runCatching { ambiguous.execute(JsonObject(emptyMap())) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("multiple servers"))
        assertNull(calledServer)
    }
}
