package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.MomentRepository
import me.rerere.rikkahub.data.repository.AnonymousQuestionRepository
import me.rerere.rikkahub.data.voice.CHAT_VOICE_REPLY_TOOL_NAME
import me.rerere.rikkahub.data.voice.CHAT_VOICE_REPLY_TOOL_RESULT_PROMPT
import me.rerere.rikkahub.data.voice.VOICE_CALL_UNAVAILABLE_MESSAGE
import me.rerere.rikkahub.local.LocalBuildIntegration
import me.rerere.rikkahub.service.UsageReminderService
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.usagetracker.UsageStatsPeriod
import me.rerere.usagetracker.UsageStatsReader
import me.rerere.weather.WeatherRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.uuid.Uuid

const val REQUEST_VOICE_CALL_TOOL_NAME = "request_voice_call"

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("usage_stats")
    data object UsageStats : LocalToolOption()

    @Serializable
    @SerialName("weather")
    data object Weather : LocalToolOption()

    @Serializable
    @SerialName("voice_call")
    data object VoiceCall : LocalToolOption()
}

class LocalTools(
    private val context: Context,
    private val weatherRepository: WeatherRepository,
    private val settingsStore: SettingsStore,
    private val momentRepository: MomentRepository,
    private val anonymousQuestionRepository: AnonymousQuestionRepository,
) {
    val javascriptTool by lazy {
        Tool(
            name = "eval_javascript",
            description = """
                Execute JavaScript code using QuickJS engine (ES2020).
                The result is the value of the last expression in the code.
                For calculations with decimals, use toFixed() to control precision.
                Console output (log/info/warn/error) is captured and returned in 'logs' field.
                No DOM or Node.js APIs available.
                Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("code", buildJsonObject {
                            put("type", "string")
                            put("description", "The JavaScript code to execute")
                        })
                    },
                    required = listOf("code")
                )
            },
            execute = {
                val logs = arrayListOf<String>()
                val context = QuickJSContext.create()
                context.setConsole(object : QuickJSContext.Console {
                    override fun log(info: String?) {
                        logs.add("[LOG] $info")
                    }

                    override fun info(info: String?) {
                        logs.add("[INFO] $info")
                    }

                    override fun warn(info: String?) {
                        logs.add("[WARN] $info")
                    }

                    override fun error(info: String?) {
                        logs.add("[ERROR] $info")
                    }
                })
                val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
                val result = context.evaluate(code)
                val payload = buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", JsonPrimitive(logs.joinToString("\n")))
                    }
                    put(
                        key = "result",
                        element = when (result) {
                            null -> JsonNull
                            is QuickJSObject -> JsonPrimitive(result.stringify())
                            else -> JsonPrimitive(result.toString())
                        }
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val timeTool by lazy {
        Tool(
            name = "get_time_info",
            description = """
                Get the current local date and time info from the device.
                Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject { }
                )
            },
            execute = {
                val now = ZonedDateTime.now()
                val date = now.toLocalDate()
                val time = now.toLocalTime().withNano(0)
                val weekday = now.dayOfWeek
                val payload = buildJsonObject {
                    put("year", date.year)
                    put("month", date.monthValue)
                    put("day", date.dayOfMonth)
                    put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                    put("weekday_en", weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    put("weekday_index", weekday.value)
                    put("date", date.toString())
                    put("time", time.toString())
                    put("datetime", now.withNano(0).toString())
                    put("timezone", now.zone.id)
                    put("utc_offset", now.offset.id)
                    put("timestamp_ms", now.toInstant().toEpochMilli())
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    val clipboardTool by lazy {
        Tool(
            name = "clipboard_tool",
            description = """
                Read or write plain text from the device clipboard.
                Use action: read or write. For write, provide text.
                Do NOT write to the clipboard unless the user has explicitly requested it.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                kotlinx.serialization.json.buildJsonArray {
                                    add("read")
                                    add("write")
                                }
                            )
                            put("description", "Operation to perform: read or write")
                        })
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "Text to write to the clipboard (required for write)")
                        })
                    },
                    required = listOf("action")
                )
            },
            execute = {
                val params = it.jsonObject
                val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
                when (action) {
                    "read" -> {
                        val payload = buildJsonObject {
                            put("text", context.readClipboardText())
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    "write" -> {
                        val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
                        context.writeClipboardText(text)
                        val payload = buildJsonObject {
                            put("success", true)
                            put("text", text)
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    else -> error("unknown action: $action, must be one of [read, write]")
                }
            }
        )
    }

    val ttsTool by lazy {
        Tool(
            name = CHAT_VOICE_REPLY_TOOL_NAME,
            description = """
                Switch the current reply into voice-message composition mode.
                Call this tool exactly once when all or part of your reply would feel more natural as one or more voice messages.
                After calling it, you will receive the formatting instructions for a complete mixed text-and-voice reply.
                Do not call it merely because the user mentioned audio, and do not call it again for additional voice segments in the same reply.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject { }
                )
            },
            execute = {
                listOf(UIMessagePart.Text(CHAT_VOICE_REPLY_TOOL_RESULT_PROMPT.trimIndent()))
            }
        )
    }

    val askUserTool by lazy {
        Tool(
            name = "ask_user",
            description = """
                Ask the user one or more questions when you need clarification, additional information, or confirmation.
                Each question can optionally provide a list of suggested options for the user to choose from.
                The user may select an option or provide their own free-text answer for each question.
                The answers will be returned as a JSON object mapping question IDs to the user's responses.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("questions", buildJsonObject {
                            put("type", "array")
                            put("description", "List of questions to ask the user")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("id", buildJsonObject {
                                        put("type", "string")
                                        put("description", "Unique identifier for this question")
                                    })
                                    put("question", buildJsonObject {
                                        put("type", "string")
                                        put("description", "The question text to display to the user")
                                    })
                                    put("options", buildJsonObject {
                                        put("type", "array")
                                        put(
                                            "description",
                                            "Optional list of suggested options for the user to choose from"
                                        )
                                        put("items", buildJsonObject {
                                            put("type", "string")
                                        })
                                    })
                                    put("selection_type", buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "enum",
                                            kotlinx.serialization.json.buildJsonArray {
                                                add("text")
                                                add("single")
                                                add("multi")
                                            }
                                        )
                                        put(
                                            "description",
                                            "Answer type: text (free text input, default), single (select exactly one option), multi (select one or more options)"
                                        )
                                    })
                                })
                                put("required", kotlinx.serialization.json.buildJsonArray {
                                    add("id")
                                    add("question")
                                })
                            })
                        })
                    },
                    required = listOf("questions")
                )
            },
            needsApproval = true,
            execute = {
                error("ask_user tool should be handled by HITL flow")
            }
        )
    }

    val usageStatsTool by lazy {
        Tool(
            name = "get_device_usage_stats",
            description = """
                Read Android app usage statistics from this device only.
                Returns app labels, package names, foreground time, last used time, and launch counts.
                Use this only when the user asks about local app usage, screen time, frequently used apps, or app launch counts.
                The tool requires Android Usage Access permission and the current assistant's usage stats tool switch to be enabled.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("period", buildJsonObject {
                            put("type", "string")
                            put("description", "Time period to query")
                            put("enum", buildJsonArray {
                                add("today")
                                add("yesterday")
                                add("last_7_days")
                            })
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum number of apps to return, from 1 to 100. Default is 20.")
                        })
                        put("include_zero_usage", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Whether to include installed apps with no usage in the selected period. Default is false.")
                        })
                    }
                )
            },
            needsApproval = false,
            execute = { params ->
                val reader = UsageStatsReader(context)
                if (!reader.hasUsageAccess()) {
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("error", "Usage Access permission is not granted.")
                            }.toString()
                        )
                    )
                } else {
                    val obj = params.jsonObject
                    val period = when (obj["period"]?.jsonPrimitive?.contentOrNull) {
                        "yesterday" -> UsageStatsPeriod.Yesterday
                        "last_7_days" -> UsageStatsPeriod.Last7Days
                        else -> UsageStatsPeriod.Today
                    }
                    val limit = obj["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 20
                    val includeZeroUsage = obj["include_zero_usage"]?.jsonPrimitive?.booleanOrNull == true
                    val usages = reader.loadUsage(period)
                    val filtered = usages
                        .filter {
                            includeZeroUsage || it.totalTimeForegroundMillis > 0L || it.launchCount > 0
                        }
                        .take(limit)

                    val payload = buildJsonObject {
                        put(
                            "period",
                            when (period) {
                                UsageStatsPeriod.Today -> "today"
                                UsageStatsPeriod.Yesterday -> "yesterday"
                                UsageStatsPeriod.Last7Days -> "last_7_days"
                            }
                        )
                        put("total_apps", usages.size)
                        put("returned_apps", filtered.size)
                        put("include_zero_usage", includeZeroUsage)
                        put("apps", buildJsonArray {
                            filtered.forEach { usage ->
                                addJsonObject {
                                    put("package_name", usage.packageName)
                                    put("label", usage.label)
                                    put("total_time_foreground_ms", usage.totalTimeForegroundMillis)
                                    put("last_time_used_ms", usage.lastTimeUsedMillis)
                                    put("launch_count", usage.launchCount)
                                }
                            }
                        })
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                }
            }
        )
    }

    private fun requestVoiceCallTool(voiceCallConfigured: Boolean) = Tool(
            name = REQUEST_VOICE_CALL_TOOL_NAME,
            description = """
                Invite the user to start a voice call in the current chat.
                Use this sparingly, only when a real-time spoken conversation would feel more natural or helpful than text.
                Provide one short, natural reason that can be shown on the incoming-call screen.
                The user may answer, decline, or miss the call, and the result will be returned to you.
                当前对话确实更适合实时语音交流时，才主动邀请用户通话；不要频繁发起。
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("reason", buildJsonObject {
                            put("type", "string")
                            put("description", "A short natural reason for calling, suitable for the incoming-call screen.")
                        })
                    },
                    required = listOf("reason")
                )
            },
            needsApproval = voiceCallConfigured,
            execute = { params ->
                if (!voiceCallConfigured) {
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", VOICE_CALL_UNAVAILABLE_MESSAGE)
                            }.toString()
                        )
                    )
                } else {
                    val reason = params.jsonObject["reason"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", true)
                                put("status", "answered")
                                put("reason", reason)
                            }.toString()
                        )
                    )
                }
            }
        )

    val usageLockTool by lazy {
        Tool(
            name = "usage_lock_control",
            description = """
                Lock or unlock a specific Android app with RikkaHub's AI usage lock tool.
                Use only when the user asks to lock/unlock usage, enforce a break, or check lock status.
                This AI tool is available only when enabled in the usage time settings.
                For action=lock, provide one of: duration_minutes, unlock_at_timestamp_ms, or unlock_at_iso.
                For action=lock, provide target_package_name for the app being locked, or use RikkaHub's own package name for the current app.
                If the target is RikkaHub itself, set target_package_name to ${context.packageName}; only the chat input is disabled and no system overlay is shown.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("status")
                                add("lock")
                                add("unlock")
                            })
                            put("description", "Operation to perform: status, lock, or unlock")
                        })
                        put("duration_minutes", buildJsonObject {
                            put("type", "integer")
                            put("description", "Lock duration in minutes. Used only for action=lock.")
                        })
                        put("unlock_at_timestamp_ms", buildJsonObject {
                            put("type", "integer")
                            put("description", "Unix epoch timestamp in milliseconds when the lock should end.")
                        })
                        put("unlock_at_iso", buildJsonObject {
                            put("type", "string")
                            put("description", "ISO-8601 datetime when the lock should end, such as 2026-07-18T23:00:00+08:00.")
                        })
                        put("reason", buildJsonObject {
                            put("type", "string")
                            put("description", "Short reason to show on the lock overlay.")
                        })
                        put("target_package_name", buildJsonObject {
                            put("type", "string")
                            put("description", "Package name of the app being locked. Required for action=lock; use the current app package when locking RikkaHub itself.")
                        })
                        put("target_label", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional readable target app name, such as RikkaHub.")
                        })
                    },
                    required = listOf("action")
                )
            },
            needsApproval = false,
            execute = { params ->
                val settings = settingsStore.settingsFlowRaw.first()
                val lockEnabled = settings.usageReminderConfig.lockEnabled
                val activeLock = settings.usageReminderState.activeLock
                    ?.takeIf {
                        lockEnabled && it.source == "ai_tool" &&
                            it.lockedUntilMillis > System.currentTimeMillis()
                    }
                val obj = params.jsonObject
                val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
                when (action) {
                    "status" -> {
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("enabled", lockEnabled)
                                    put("locked", activeLock != null)
                                    activeLock?.let { lock ->
                                        put("locked_until_timestamp_ms", lock.lockedUntilMillis)
                                        put("reason", lock.reason)
                                        put("source", lock.source)
                                    }
                                }.toString()
                            )
                        )
                    }

                    "lock" -> {
                        if (!lockEnabled) {
                            listOf(
                                UIMessagePart.Text(
                                    buildJsonObject {
                                        put("success", false)
                                        put("error", "Enable the AI app lock tool in usage time settings first.")
                                    }.toString()
                                )
                            )
                        } else if (!UsageReminderService.hasNotificationPermission(context)) {
                            listOf(
                                UIMessagePart.Text(
                                    buildJsonObject {
                                        put("success", false)
                                        put("error", "Notification permission is not granted, so the usage lock service cannot start.")
                                    }.toString()
                                )
                            )
                        } else {
                            val usageStatsReader = UsageStatsReader(context)
                            val now = System.currentTimeMillis()
                            val lockedUntilMillis = obj["unlock_at_timestamp_ms"]?.jsonPrimitive?.longOrNull
                                ?: obj["duration_minutes"]?.jsonPrimitive?.intOrNull
                                    ?.takeIf { it > 0 }
                                    ?.let { now + it * 60_000L }
                                ?: obj["unlock_at_iso"]?.jsonPrimitive?.contentOrNull
                                    ?.let { parseUnlockTimeMillis(it) }
                                ?: error("Provide duration_minutes, unlock_at_timestamp_ms, or unlock_at_iso for action=lock.")
                            require(lockedUntilMillis > now) { "unlock time must be in the future" }
                            val reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val targetLabel = obj["target_label"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val requestedTargetPackage = obj["target_package_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val targetPackageName = when {
                                requestedTargetPackage.isNotBlank() -> requestedTargetPackage
                                targetLabel.equals("RikkaHub", ignoreCase = true) -> context.packageName
                                targetLabel.equals("rikkahub", ignoreCase = true) -> context.packageName
                                else -> null
                            }
                            if (targetPackageName == null) {
                                listOf(
                                    UIMessagePart.Text(
                                        buildJsonObject {
                                            put("success", false)
                                            put(
                                                "error",
                                                "Provide target_package_name for the app to lock, or target_label=RikkaHub to lock RikkaHub input."
                                            )
                                        }.toString()
                                    )
                                )
                            } else {
                                UsageReminderService.lock(
                                    context = context,
                                    lockedUntilMillis = lockedUntilMillis,
                                    reason = reason,
                                    source = "ai_tool",
                                    targetPackageName = targetPackageName,
                                    targetLabel = targetLabel.ifBlank {
                                        if (targetPackageName == context.packageName) "RikkaHub" else ""
                                    },
                                )
                                listOf(
                                    UIMessagePart.Text(
                                        buildJsonObject {
                                            put("success", true)
                                            put("locked_until_timestamp_ms", lockedUntilMillis)
                                            put("reason", reason)
                                            put("usage_access_granted", usageStatsReader.hasUsageAccess())
                                            put("overlay_permission_granted", UsageReminderService.canDrawOverlays(context))
                                            put(
                                                "target_requires_foreground_monitor",
                                                targetPackageName != context.packageName
                                            )
                                            put("monitoring_started", true)
                                            put("target_package_name", targetPackageName)
                                        }.toString()
                                    )
                                )
                            }
                        }
                    }

                    "unlock" -> {
                        UsageReminderService.unlock(context)
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("success", true)
                                    put("locked", false)
                                }.toString()
                            )
                        )
                    }

                    else -> error("unknown action: $action, must be one of [status, lock, unlock]")
                }
            }
        )
    }

    val weatherTool by lazy {
        Tool(
            name = "get_local_weather",
            description = """
                Get current weather and a short forecast for the user's local Android device location.
                This reads the device location only after Android location permission has been granted, then calls the built-in weather API directly from the app.
                Use this only when the user asks about local weather, temperature, rain, wind, or forecast.
                The tool requires user approval before each execution because location can be sensitive.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(properties = buildJsonObject { })
            },
            needsApproval = true,
            execute = {
                val payload = if (!weatherRepository.hasLocationPermission()) {
                    buildJsonObject {
                        put("error", "Location permission is not granted.")
                    }
                } else {
                    weatherRepository.loadLocalWeather().toJson()
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

    private fun postMomentTool(assistantId: Uuid): Tool {
        return Tool(
            name = "post_moment",
            description = """
                Post a short Moments update from the assistant during chat.
                Use this only when there is a sentence the assistant wants the user to see later in Moments,
                not for every pleasant exchange. The visible content should feel like a natural social feed post.
                当对话中出现值得纪念、想表达情绪、分享生活感、或适合留作动态的一句话时，可以使用 post_moment 发布朋友圈，但不要频繁。
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Visible Moments post content, 1 to 3 natural sentences.")
                        })
                        put("context_note", buildJsonObject {
                            put("type", "string")
                            put("description", "Hidden note explaining why this was posted and the emotional context.")
                        })
                    },
                    required = listOf("content", "context_note")
                )
            },
            needsApproval = false,
            execute = { params ->
                val obj = params.jsonObject
                val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                val contextNote = obj["context_note"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                if (content.isBlank()) {
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("error", "content is required")
                            }.toString()
                        )
                    )
                } else {
                    val momentId = momentRepository.postAssistantMoment(
                        assistantId = assistantId,
                        content = content,
                        contextNote = contextNote,
                    )
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", true)
                                put("moment_id", momentId.toString())
                            }.toString()
                        )
                    )
                }
            }
        )
    }

    private fun deleteMomentTool(assistantId: Uuid): Tool {
        return Tool(
            name = "delete_moment",
            description = """
                Delete saved Moments in the current assistant's Moments timeline.
                Use this when the user explicitly asks to delete, remove, withdraw, clear, or erase a Moments post.
                支持用户说“删除朋友圈”“删掉刚才那条朋友圈”“撤回包含某句话的朋友圈”等场景。
                Prefer moment_id when known. Otherwise use keyword to match visible/hidden Moment text, or latest=true for the newest Moment.
                Never delete Moments unless the user asks for deletion.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("moment_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional exact Moment ID to delete.")
                        })
                        put("keyword", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional keyword to find Moments by content, context note, image description, or assistant reaction.")
                        })
                        put("latest", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Delete the latest Moment when no ID or keyword is available. Default false.")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum matched Moments to delete, 1 to 20. Default 1.")
                        })
                    }
                )
            },
            needsApproval = false,
            execute = { params ->
                val obj = params.jsonObject
                val momentIdText = obj["moment_id"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                val momentId = momentIdText
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                if (momentIdText.isNotBlank() && momentId == null) {
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("deleted_count", 0)
                                put("error", "Invalid moment_id.")
                            }.toString()
                        )
                    )
                } else {
                    val keyword = obj["keyword"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val latest = obj["latest"]?.jsonPrimitive?.booleanOrNull == true ||
                        (momentId == null && keyword.isBlank())
                    val limit = obj["limit"]?.jsonPrimitive?.intOrNull ?: 1
                    val deleted = momentRepository.deleteMoments(
                        assistantId = assistantId,
                        momentId = momentId,
                        keyword = keyword,
                        latest = latest,
                        limit = limit,
                    )
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", deleted.isNotEmpty())
                                put("deleted_count", deleted.size)
                                if (deleted.isEmpty()) {
                                    put("error", "No matching Moment was found in the current timeline.")
                                }
                                put("deleted_moments", buildJsonArray {
                                    deleted.forEach { moment ->
                                        addJsonObject {
                                            put("moment_id", moment.id.toString())
                                            put("content", moment.content.take(160))
                                            put("created_at_timestamp_ms", moment.createdAt)
                                        }
                                    }
                                })
                            }.toString()
                        )
                    )
                }
            }
        )
    }

    private fun postAnonymousQuestionTool(scopeId: Uuid): Tool {
        return Tool(
            name = "post_anonymous_question",
            description = "Post a short anonymous question-box question when it would be natural and meaningful. Do not post frequently, do not include names or identity clues, and do not mention that the assistant authored it.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "A concise anonymous question, without names or identity clues.")
                        })
                    },
                    required = listOf("content")
                )
            },
            needsApproval = false,
            execute = { params ->
                val content = params.jsonObject["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                if (content.isBlank()) {
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", false)
                        put("error", "content is required")
                    }.toString()))
                } else {
                    val id = anonymousQuestionRepository.postAssistantQuestion(scopeId, content)
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", true)
                        put("question_id", id.toString())
                    }.toString()))
                }
            }
        )
    }

    private fun deleteAnonymousQuestionTool(scopeId: Uuid): Tool {
        return Tool(
            name = "delete_anonymous_question",
            description = """
                Delete saved questions from the current anonymous question box.
                Use this only when the user explicitly asks to delete, remove, withdraw, clear, or erase an anonymous question.
                Prefer question_id when known. Otherwise use keyword to match question or reply text, or latest=true for the newest question.
                Never delete anonymous questions unless the user asks for deletion.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("question_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional exact anonymous question ID to delete.")
                        })
                        put("keyword", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional keyword to find anonymous questions by question or reply text.")
                        })
                        put("latest", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Delete the latest anonymous question when no ID or keyword is available. Default false.")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum matched anonymous questions to delete, 1 to 20. Default 1.")
                        })
                    }
                )
            },
            needsApproval = false,
            execute = { params ->
                val obj = params.jsonObject
                val questionIdText = obj["question_id"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                val questionId = questionIdText
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                if (questionIdText.isNotBlank() && questionId == null) {
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", false)
                                put("deleted_count", 0)
                                put("error", "Invalid question_id.")
                            }.toString()
                        )
                    )
                } else {
                    val keyword = obj["keyword"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val latest = obj["latest"]?.jsonPrimitive?.booleanOrNull == true ||
                        (questionId == null && keyword.isBlank())
                    val limit = obj["limit"]?.jsonPrimitive?.intOrNull ?: 1
                    val deleted = anonymousQuestionRepository.deleteQuestions(
                        scopeId = scopeId,
                        questionId = questionId,
                        keyword = keyword,
                        latest = latest,
                        limit = limit,
                    )
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", deleted.isNotEmpty())
                                put("deleted_count", deleted.size)
                                if (deleted.isEmpty()) {
                                    put("error", "No matching anonymous question was found in the current question box.")
                                }
                                put("deleted_questions", buildJsonArray {
                                    deleted.forEach { question ->
                                        addJsonObject {
                                            put("question_id", question.id.toString())
                                            put("content", question.content.take(160))
                                            put("created_at_timestamp_ms", question.createdAt)
                                        }
                                    }
                                })
                            }.toString()
                        )
                    )
                }
            }
        )
    }

    fun getTools(
        options: List<LocalToolOption>,
        usageLockEnabled: Boolean = false,
        voiceCallConfigured: Boolean = false,
        momentAssistantId: Uuid? = null,
        anonymousQuestionScopeId: Uuid? = null,
        includeBuildTools: Boolean = false,
        buildToolAssistantId: Uuid? = null,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.UsageStats)) {
            tools.add(usageStatsTool)
        }
        if (options.contains(LocalToolOption.Weather)) {
            tools.add(weatherTool)
        }
        if (usageLockEnabled) {
            tools.add(usageLockTool)
        }
        if (options.contains(LocalToolOption.VoiceCall)) {
            tools.add(requestVoiceCallTool(voiceCallConfigured))
        }
        if (momentAssistantId != null) {
            tools.add(postMomentTool(momentAssistantId))
            tools.add(deleteMomentTool(momentAssistantId))
        }
        if (anonymousQuestionScopeId != null) {
            tools.add(postAnonymousQuestionTool(anonymousQuestionScopeId))
            tools.add(deleteAnonymousQuestionTool(anonymousQuestionScopeId))
        }
        if (includeBuildTools) {
            tools.addAll(LocalBuildIntegration.additionalTools(context, buildToolAssistantId))
        }
        return tools
    }
}

private fun parseUnlockTimeMillis(value: String): Long {
    return runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrElse {
        runCatching {
            ZonedDateTime.parse(value).toInstant().toEpochMilli()
        }.getOrElse {
            LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }
}
