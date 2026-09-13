package me.rerere.usagetracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class UsageReminderConfig(
    val rules: List<UsageReminderRule> = emptyList(),
    val reminderMessages: List<String> = emptyList(),
    val lockEnabled: Boolean = false,
)

@Serializable
data class UsageReminderRule(
    val packageName: String,
    val label: String,
    val thresholdMinutes: Int = 60,
    val enabled: Boolean = true,
)

@Serializable
data class UsageReminderState(
    val date: String = todayKey(),
    val appStates: Map<String, UsageReminderAppState> = emptyMap(),
    val activeLock: UsageReminderLock? = null,
)

@Serializable
data class UsageReminderAppState(
    val reminderCount: Int = 0,
    val ignored: Boolean = false,
    val lastEventTimeMillis: Long = 0L,
    val lastReminderUsageMillis: Long = 0L,
    val lastReminderEventTimeMillis: Long = 0L,
)

@Serializable
data class UsageReminderLock(
    val lockedUntilMillis: Long,
    val reason: String = "",
    val source: String = "usage_limit",
    val targetPackageName: String? = null,
    val targetLabel: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
)

data class ForegroundAppEvent(
    val packageName: String,
    val timestampMillis: Long,
)

private val usageReminderMessagesJson = Json {
    ignoreUnknownKeys = true
}

internal fun parseUsageReminderMessages(content: String): List<String> {
    val root = usageReminderMessagesJson.parseToJsonElement(content)
    require(root is JsonArray) { "JSON 根节点必须是数组" }

    return root.mapIndexed { index, element ->
        val message = when (element) {
            is JsonPrimitive -> {
                require(element.isString) { "第 ${index + 1} 项必须是字符串或包含 message 字段的对象" }
                element.content
            }

            is JsonObject -> element["message"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("第 ${index + 1} 项缺少 message 字段")

            else -> throw IllegalArgumentException("第 ${index + 1} 项格式不正确")
        }
        message.trim()
    }.filter { it.isNotEmpty() }
        .distinct()
}

fun todayKey(): String = LocalDate.now(ZoneId.systemDefault()).toString()
