package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.ProviderResponseParseException
import me.rerere.search.SearchToolArgumentException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionFailureTest {
    @Test
    fun `tool failure keeps stage and http status without exception text`() {
        val serialized = toolExecutionFailure("execution", HttpException("secret response body", 429))
        val error = Json.parseToJsonElement(serialized).jsonObject.getValue("error").jsonObject

        assertEquals("remote_service", error.getValue("stage").jsonPrimitive.contentOrNull)
        assertEquals("429", error.getValue("httpStatus").jsonPrimitive.contentOrNull)
        assertFalse(serialized.contains("secret response body"))
        assertFalse(serialized.contains("stackTrace"))
    }

    @Test
    fun `tool user explanation reflects the known failure stage`() {
        val invalidArguments = Json.parseToJsonElement(toolExecutionFailure("argument_parsing"))
        val rateLimited = Json.parseToJsonElement(toolExecutionFailure("execution", HttpException("opaque", 429)))
        val unauthorized = Json.parseToJsonElement(toolExecutionFailure("execution", HttpException("opaque", 401)))
        val missingResource = Json.parseToJsonElement(toolExecutionFailure("execution", HttpException("opaque", 404)))
        val parseFailure = Json.parseToJsonElement(toolExecutionFailure("response_parsing"))
        val mcpFailure = Json.parseToJsonElement(
            toolExecutionFailure("execution", ToolExecutionException("remote_tool", "mcp_tool_reported_failure")),
        )

        assertEquals("工具参数缺少必填项或格式无效。", invalidArguments.toolExecutionFailureMessage())
        assertEquals("第三方服务限制了请求频率或额度（HTTP 429）。", rateLimited.toolExecutionFailureMessage())
        assertEquals("第三方服务身份验证失败（HTTP 401）。", unauthorized.toolExecutionFailureMessage())
        assertEquals("第三方服务未找到请求的资源（HTTP 404）。", missingResource.toolExecutionFailureMessage())
        assertEquals("工具响应解析或格式校验失败。", parseFailure.toolExecutionFailureMessage())
        assertEquals("第三方工具报告执行失败。", mcpFailure.toolExecutionFailureMessage())
        assertTrue(toolExecutionFailure("resolution").contains("tool_not_found"))
    }

    @Test
    fun `arbitrary tool errors are not mistaken for app diagnostics`() {
        assertNull(
            Json.parseToJsonElement("""{"error":{"message":"remote business error"}}""")
                .toolExecutionFailureMessage(),
        )
        assertNull(
            Json.parseToJsonElement("""{"error":{"message":"Tool operation failed","stage":{},"code":[]}}""")
                .toolExecutionFailureMessage(),
        )
    }

    @Test
    fun `only network evidence or explicit connection stage is reported as connection failure`() {
        val localIoFailure = Json.parseToJsonElement(
            toolExecutionFailure("execution", IOException("local file read failed")),
        )
        val networkFailure = Json.parseToJsonElement(
            toolExecutionFailure("execution", ConnectException("connection refused")),
        )
        val interruptedConnection = Json.parseToJsonElement(
            toolExecutionFailure("execution", SocketException("connection reset")),
        )
        val knownConnectionStage = Json.parseToJsonElement(
            toolExecutionFailure("execution", ToolExecutionException("connection", "mcp_client_unavailable")),
        )

        assertEquals("execution", localIoFailure.jsonObject.getValue("error").jsonObject.getValue("stage").jsonPrimitive.contentOrNull)
        assertEquals("工具执行失败。", localIoFailure.toolExecutionFailureMessage())
        assertEquals("工具连接失败。", networkFailure.toolExecutionFailureMessage())
        assertEquals("工具连接中断，具体阶段无法确认。", interruptedConnection.toolExecutionFailureMessage())
        assertEquals("工具连接不可用。", knownConnectionStage.toolExecutionFailureMessage())
    }

    @Test
    fun `provider response parse failures are classified without exposing their cause`() {
        val parseFailure = ProviderResponseParseException(
            "Search response format was invalid",
            IllegalArgumentException("provider body secret"),
        )
        val serialized = toolExecutionFailure("execution", parseFailure)

        assertEquals(
            "response_parsing",
            Json.parseToJsonElement(serialized).jsonObject.getValue("error").jsonObject
                .getValue("stage").jsonPrimitive.contentOrNull,
        )
        assertFalse(serialized.contains("provider body secret"))
    }

    @Test
    fun `search configuration and provider failures keep their known scope`() {
        val missingKey = toolExecutionFailure(
            "execution",
            HttpException(
                "Search service API key is not configured",
                providerCode = "missing_api_key",
                providerType = "configuration",
            ),
        )
        val providerFailure = toolExecutionFailure(
            "execution",
            HttpException(
                "Search provider reported a request failure",
                providerCode = "1001",
                providerType = "remote_service",
            ),
        )
        val providerJson = Json.parseToJsonElement(providerFailure).jsonObject.getValue("error").jsonObject

        assertEquals("搜索服务未配置 API Key，请检查服务设置。", Json.parseToJsonElement(missingKey).toolExecutionFailureMessage())
        assertEquals("第三方服务报告请求失败。", Json.parseToJsonElement(providerFailure).toolExecutionFailureMessage())
        assertEquals("remote_service", providerJson.getValue("stage").jsonPrimitive.contentOrNull)
        assertEquals("1001", providerJson.getValue("providerCode").jsonPrimitive.contentOrNull)
        assertFalse(providerFailure.contains("request failure"))
        val paymentFailure = toolExecutionFailure(
            "execution",
            HttpException("opaque", statusCode = 401, providerCode = "payment_issue", providerType = "remote_service"),
        )
        assertTrue(Json.parseToJsonElement(paymentFailure).toolExecutionFailureMessage().orEmpty().contains("付款状态"))
        val missingScript = toolExecutionFailure(
            "execution",
            HttpException("private script", providerCode = "missing_search_script", providerType = "configuration"),
        )
        assertEquals("自定义搜索服务未配置搜索脚本，请检查脚本设置。", Json.parseToJsonElement(missingScript).toolExecutionFailureMessage())
    }

    @Test
    fun `search tool argument errors are classified separately from execution errors`() {
        val serialized = toolExecutionFailure("execution", SearchToolArgumentException("query"))
        val error = Json.parseToJsonElement(serialized).jsonObject.getValue("error").jsonObject

        assertEquals("argument_parsing", error.getValue("stage").jsonPrimitive.contentOrNull)
        assertEquals("query", error.getValue("argument").jsonPrimitive.contentOrNull)
        assertEquals("工具参数缺少必填项或格式无效。", Json.parseToJsonElement(serialized).toolExecutionFailureMessage())
    }
}
