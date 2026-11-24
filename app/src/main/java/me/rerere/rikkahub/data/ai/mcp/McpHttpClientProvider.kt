package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class McpHttpClientProvider<T>(
    createClient: () -> T,
) {
    private val client by lazy(LazyThreadSafetyMode.SYNCHRONIZED, createClient)

    // The first MCP use must not build the OkHttp/Ktor stack on the UI thread.
    suspend fun <R> withClient(block: (T) -> R): R =
        withContext(Dispatchers.IO) { block(client) }
}
