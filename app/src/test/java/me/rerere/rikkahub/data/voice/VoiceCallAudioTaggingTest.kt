package me.rerere.rikkahub.data.voice

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock

class VoiceCallAudioTaggingTest {
    @Test
    fun mapsAllowedTagIdsToClientOwnedSpeechTags() {
        val parsed = parseVoiceCallAudioTagResponse(
            """
            {
              "segments": [
                {"tagId":"BREATH","text":"I am here."},
                {"tagId":"EMM","text":"What happened?"}
              ]
            }
            """.trimIndent()
        )

        assertEquals("I am here.\nWhat happened?", parsed.visibleText)
        assertEquals("[breath] I am here.\n[emm] What happened?", parsed.speechText)
        assertEquals("[breath] I am here.\n[emm] What happened?", parsed.displayText)
    }

    @Test
    fun replacesInventedTagIdWithSafeFallback() {
        val parsed = parseVoiceCallAudioTagResponse(
            """{"segments":[{"tagId":"SPEAKING_WITH_A_WARM_SMILE","text":"Welcome back."}]}"""
        )

        assertEquals("Welcome back.", parsed.visibleText)
        assertEquals("[breath] Welcome back.", parsed.speechText)
        assertEquals("[breath fallback:unknown_tag] Welcome back.", parsed.displayText)
        assertFalse(parsed.speechText.contains("warm smile"))
    }

    @Test
    fun removesModelWrittenDirectionsFromSegmentText() {
        val parsed = parseVoiceCallAudioTagResponse(
            """{"segments":[{"tagId":"LAUGHS","text":"[shouting happily] We did it!"}]}"""
        )

        assertEquals("We did it!", parsed.visibleText)
        assertEquals("[laughs] We did it!", parsed.speechText)
    }

    @Test
    fun fallsBackToPlainTextWhenModelIgnoresStructuredProtocol() {
        val parsed = parseVoiceCallAudioTagResponse("I am still here.")

        assertEquals("I am still here.", parsed.visibleText)
        assertEquals("[breath] I am still here.", parsed.speechText)
        assertEquals("[breath fallback:invalid_response] I am still here.", parsed.displayText)
    }

    @Test
    fun preservesAllowedTagsFromLegacyV3Output() {
        val parsed = parseVoiceCallAudioTagResponse(
            "[sighs] I understand.\n[laughs] We can try again."
        )

        assertEquals("I understand.\nWe can try again.", parsed.visibleText)
        assertEquals("[sighs] I understand.\n[laughs] We can try again.", parsed.speechText)
        assertEquals("[sighs] I understand.\n[laughs] We can try again.", parsed.displayText)
        assertTrue(parsed.segments.all { it.selectionSource == VoiceCallTagSelectionSource.LEGACY })
    }

    @Test
    fun storesPlainPresentationAndKeepsTaggedSpeechInMetadata() {
        val rawMessage = assistantMessage(
            """{"segments":[{"tagId":"SIGHS","text":"I understand."}]}"""
        )

        val presented = rawMessage.toVoiceCallAudioTagPresentation()

        assertEquals("I understand.", presented.toText())
        assertEquals("[sighs] I understand.", presented.voiceCallSpeechTextOrPlainText())
        assertEquals("[sighs] I understand.", presented.voiceCallDisplayTextOrPlainText())
        assertFalse(presented.toText().contains("[sighs]"))
    }

    @Test
    fun appliesSecondPassTagsWithoutChangingPrimaryReply() {
        val primaryReply = assistantMessage("I understand. We can try again.")

        val taggedReply = primaryReply.withSelectedVoiceCallAudioTagIds(
            selectedTagIds = listOf("SIGHS", "LAUGHS"),
            format = VoiceCallAudioTagFormat.ELEVEN_LABS_V3,
        )

        assertEquals("I understand. We can try again.", taggedReply.toText())
        assertEquals(
            "[sighs] I understand.\n[laughs] We can try again.",
            taggedReply.voiceCallSpeechTextOrPlainText(),
        )
    }

    @Test
    fun appliesTagIdsToClientOwnedSegmentsWithoutModelTextCopy() {
        val primaryReply = assistantMessage("你嘴上说着一点点。其实心里想的是我，对吧？")

        val taggedReply = primaryReply.withSelectedVoiceCallAudioTagIds(
            selectedTagIds = listOf("CHUCKLE", "EMM"),
            format = VoiceCallAudioTagFormat.ELEVEN_LABS_V3,
        )

        assertEquals("你嘴上说着一点点。其实心里想的是我，对吧？", taggedReply.toText())
        assertEquals(
            "[chuckle] 你嘴上说着一点点。\n[emm] 其实心里想的是我，对吧？",
            taggedReply.voiceCallSpeechTextOrPlainText(),
        )
        assertEquals(
            "[chuckle] 你嘴上说着一点点。\n[emm] 其实心里想的是我，对吧？",
            taggedReply.voiceCallDisplayTextOrPlainText(),
        )
    }

    @Test
    fun rendersSharedCommonTagsWithMiniMaxRoundDelimiters() {
        val primaryReply = assistantMessage("别这样说嘛。你突然这么认真，我都不知道怎么接了。继续聊。")

        val taggedReply = primaryReply.withSelectedVoiceCallAudioTagIds(
            selectedTagIds = listOf("LAUGHS", "CHUCKLE", null),
            format = VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8,
        )

        assertEquals(
            "(laughs)，别这样说嘛。\n(chuckle)，你突然这么认真，我都不知道怎么接了。\n继续聊。",
            taggedReply.voiceCallSpeechTextOrPlainText(),
        )
        assertTrue(VoiceCallAudioTag.LAUGHS.isCommon)
        assertTrue(VoiceCallAudioTag.CHUCKLE.isCommon)
    }

    @Test
    fun replacesMiniMaxLeadingInterjectionsInSpeechAndKeepsPrimaryReply() {
        val primaryReply = assistantMessage("嘿嘿，我好开心。啊.....这样啊")

        val taggedReply = primaryReply.withSelectedVoiceCallAudioTagIds(
            selectedTagIds = listOf("LAUGHS", "SIGHS"),
            format = VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8,
        )

        assertEquals("嘿嘿，我好开心。啊.....这样啊", taggedReply.toText())
        assertEquals(
            "(laughs)，我好开心。\n(sighs)，这样啊",
            taggedReply.voiceCallSpeechTextOrPlainText(),
        )
        assertEquals(
            "(laughs)，我好开心。\n(sighs)，这样啊",
            taggedReply.voiceCallDisplayTextOrPlainText(),
        )
    }
    @Test
    fun appliesExactModelProvidedReplacementWithoutChangingPrimaryReply() {
        val primaryReply = assistantMessage("\u554a\u554a\uff0c\u8fd9\u6837\u554a")

        val taggedReply = primaryReply.withSelectedVoiceCallAudioTagAssignments(
            selectedAssignments = listOf(
                VoiceCallAudioTagAssignment(
                    tagId = "SIGHS",
                    replacementText = "\u554a\u554a",
                )
            ),
            format = VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8,
        )

        assertEquals("\u554a\u554a\uff0c\u8fd9\u6837\u554a", taggedReply.toText())
        assertEquals(
            "(sighs)\uff0c\u8fd9\u6837\u554a",
            taggedReply.voiceCallSpeechTextOrPlainText(),
        )
    }

    @Test
    fun enablesSharedTaggingOnlyForMiniMaxSpeechTwoPointEight() {
        assertEquals(
            VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8,
            TTSProviderSetting.MiniMax(model = "speech-2.8-turbo").voiceCallAudioTagFormatOrNull(),
        )
        assertEquals(
            null,
            TTSProviderSetting.MiniMax(model = "speech-2.6-turbo").voiceCallAudioTagFormatOrNull(),
        )
    }

    @Test
    fun preservesMiniMaxSpeechTwoPointSixModelAndGlobalEmotionSetting() {
        val setting = TTSProviderSetting.MiniMax(
            model = "speech-2.6-hd",
            emotion = "calm",
        )

        assertEquals("speech-2.6-hd", setting.model)
        assertEquals("calm", setting.emotion)
    }
    @Test
    fun reportsInvalidToolArgumentsWhenTagIdCountDoesNotMatchClientSegments() {
        val primaryReply = assistantMessage("第一句。第二句？")

        val taggedReply = primaryReply.withSelectedVoiceCallAudioTagIds(
            selectedTagIds = listOf("BREATH"),
            format = VoiceCallAudioTagFormat.ELEVEN_LABS_V3,
        )

        assertEquals(
            "[breath fallback:invalid_tool_arguments] 第一句。第二句？",
            taggedReply.voiceCallDisplayTextOrPlainText(),
        )
    }

    @Test
    fun exposesMissingToolCallWithoutChangingPrimaryReply() {
        val primaryReply = assistantMessage("I am still here.")

        val taggedReply = primaryReply.withSelectedVoiceCallAudioTagIds(
            selectedTagIds = null,
            format = VoiceCallAudioTagFormat.ELEVEN_LABS_V3,
            selectionFailureReason = VoiceCallTaggingFallbackReason.MISSING_TOOL_CALL,
        )

        assertEquals("I am still here.", taggedReply.toText())
        assertEquals("[breath] I am still here.", taggedReply.voiceCallSpeechTextOrPlainText())
        assertEquals(
            "[breath fallback:missing_tool_call] I am still here.",
            taggedReply.voiceCallDisplayTextOrPlainText(),
        )
    }

    @Test
    fun tagSelectionToolSchemaContainsOnlyClientOwnedTagIds() {
        val tool = createVoiceCallAudioTagSelectionTool(
            segmentCount = 2,
            format = VoiceCallAudioTagFormat.ELEVEN_LABS_V3,
        ) { }
        val schema = tool.parameters() as InputSchema.Obj
        val assignments = schema.properties.getValue("assignments").jsonObject
        val assignmentItem = assignments.getValue("items").jsonObject
        val tagId = assignmentItem
            .getValue("properties")
            .jsonObject
            .getValue("tagId")
            .jsonObject
        val allowedIds = tagId.getValue("enum").jsonArray.map { it.jsonPrimitive.content }

        assertEquals(2, assignments.getValue("minItems").jsonPrimitive.int)
        assertEquals(2, assignments.getValue("maxItems").jsonPrimitive.int)
        assertEquals(VoiceCallAudioTag.entries.map { it.id }, allowedIds)
    }

    @Test
    fun miniMaxToolAllowsValidatedNoTagAssignments() {
        val tool = createVoiceCallAudioTagSelectionTool(
            segmentCount = 2,
            format = VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8,
        ) { }
        val schema = tool.parameters() as InputSchema.Obj
        val allowedIds = schema.properties
            .getValue("assignments")
            .jsonObject
            .getValue("items")
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("tagId")
            .jsonObject
            .getValue("enum")
            .jsonArray
            .map { it.jsonPrimitive.content }

        assertEquals(
            VoiceCallAudioTag.entries.map { it.id } + NO_VOICE_CALL_AUDIO_TAG_ID,
            allowedIds,
        )
        val assignmentProperties = schema.properties
            .getValue("assignments")
            .jsonObject
            .getValue("items")
            .jsonObject
            .getValue("properties")
            .jsonObject
        assertTrue(assignmentProperties.containsKey("replacementText"))

        val selectedTagIds = validateVoiceCallAudioTagAssignments(
            arguments = Json.parseToJsonElement(
                """
                {
                  "assignments": [
                    {"segmentIndex":0,"tagId":"LAUGHS","replacementText":"\u563f\u563f"},
                    {"segmentIndex":1,"tagId":"NONE","replacementText":""}
                  ]
                }
                """.trimIndent()
            ),
            segmentCount = 2,
            format = VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8,
        )

        assertEquals(listOf("LAUGHS", null), selectedTagIds)
    }

    @Test
    fun validatesAndOrdersToolAssignmentsByClientSegmentIndex() {
        val selectedTagIds = validateVoiceCallAudioTagAssignments(
            arguments = Json.parseToJsonElement(
                """
                {
                  "assignments": [
                    {"segmentIndex":1,"tagId":"EMM"},
                    {"segmentIndex":0,"tagId":"CHUCKLE"}
                  ]
                }
                """.trimIndent()
            ),
            segmentCount = 2,
            format = VoiceCallAudioTagFormat.ELEVEN_LABS_V3,
        )

        assertEquals(listOf("CHUCKLE", "EMM"), selectedTagIds)
    }

    @Test
    fun rejectsInventedToolTagIds() {
        val selectedTagIds = validateVoiceCallAudioTagAssignments(
            arguments = Json.parseToJsonElement(
                """
                {
                  "assignments": [
                    {"segmentIndex":0,"tagId":"SPEAKING_WITH_A_WARM_SMILE"}
                  ]
                }
                """.trimIndent()
            ),
            segmentCount = 1,
            format = VoiceCallAudioTagFormat.ELEVEN_LABS_V3,
        )

        assertEquals(null, selectedTagIds)
    }

    @Test
    fun hidesPartialStructuredResponseUntilGenerationFinishes() {
        val partial = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("""{"segments":[{"tagId":"BREATH"""")),
        )

        assertTrue(partial.toVoiceCallAudioTagPresentation().toText().isBlank())
    }

    @Test
    fun stripsKnownVoiceTagsFromNormalContextButPreservesOtherBrackets() {
        val historicalReply = UIMessage.assistant(
            "[quietly] Keep this private.\n" +
                "[speaking with a warm smile] Welcome back.\n" +
                "[project note] Preserve this label."
        )

        val normalContext = historicalReply.withoutVoiceCallAudioTagsForNormalContext()

        assertEquals(
            "Keep this private.\nWelcome back.\n[project note] Preserve this label.",
            normalContext.toText(),
        )
    }

    @Test
    fun preservesUserWrittenTagTextInNormalContext() {
        val userMessage = UIMessage.user("What does [quietly] mean?")

        val normalContext = userMessage.withoutVoiceCallAudioTagsForNormalContext()

        assertEquals("What does [quietly] mean?", normalContext.toText())
    }

    @Test
    fun englishOnlyTaggingSkipsChineseAndMixedSegments() {
        val segments = listOf(
            "你好。",
            "Hello there!",
            "中文 mixed English.",
            "How are you?",
        )

        assertEquals(
            listOf(1, 3),
            selectVoiceCallAudioTaggingSegmentIndexes(segments, englishOnly = true),
        )
    }

    @Test
    fun englishOnlyTaggingRuleIsIndependentOfVoiceTagFormat() {
        val segments = listOf("纯中文。", "English only!", "中文 and English.")
        val selectedIndexes = selectVoiceCallAudioTaggingSegmentIndexes(
            segments = segments,
            englishOnly = true,
        )

        VoiceCallAudioTagFormat.entries.forEach { format ->
            assertEquals(format.name, listOf(1), selectedIndexes)
        }
    }

    @Test
    fun mapsEnglishOnlyAssignmentsWithoutTaggingChineseSegments() {
        val primaryReply = assistantMessage("你好。Hello there!")

        val taggedReply = primaryReply.withSelectedVoiceCallAudioTagAssignments(
            selectedAssignments = listOf(VoiceCallAudioTagAssignment(tagId = "LAUGHS")),
            format = VoiceCallAudioTagFormat.ELEVEN_LABS_V3,
            taggingSegmentIndexes = listOf(1),
        )

        assertEquals(
            "你好。\n[laughs] Hello there!",
            taggedReply.voiceCallSpeechTextOrPlainText(),
        )
    }

    @Test
    fun englishOnlyFallbackDoesNotAddTagsToChineseSegments() {
        val primaryReply = assistantMessage("你好。Hello there!")

        val taggedReply = primaryReply.withSelectedVoiceCallAudioTagAssignments(
            selectedAssignments = null,
            format = VoiceCallAudioTagFormat.ELEVEN_LABS_V3,
            taggingSegmentIndexes = listOf(1),
            selectionFailureReason = VoiceCallTaggingFallbackReason.MISSING_TOOL_CALL,
        )

        assertEquals(
            "你好。\n[breath fallback:missing_tool_call] Hello there!",
            taggedReply.voiceCallDisplayTextOrPlainText(),
        )
    }

    @Test
    fun cleansElevenLabsSquareBracketTagsFromMiniMaxSpeechCopy() {
        val primaryReply = assistantMessage("[laughs] 嘿嘿，我好开心。[pause] 然后呢？")

        val taggedReply = primaryReply.withSelectedVoiceCallAudioTagIds(
            selectedTagIds = listOf("LAUGHS", "EMM"),
            format = VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8,
        )

        assertEquals("[laughs] 嘿嘿，我好开心。[pause] 然后呢？", taggedReply.toText())
        assertEquals(
            "(laughs)，我好开心。\n(emm)，然后呢？",
            taggedReply.voiceCallSpeechTextOrPlainText(),
        )
        assertEquals(
            "(laughs)，我好开心。\n(emm)，然后呢？",
            taggedReply.voiceCallDisplayTextOrPlainText(),
        )
    }

    @Test
    fun miniMaxFallbackCleansSquareBracketTags() {
        val primaryReply = assistantMessage("[laughs] 我很好。")

        val taggedReply = primaryReply.withSelectedVoiceCallAudioTagIds(
            selectedTagIds = null,
            format = VoiceCallAudioTagFormat.MINIMAX_SPEECH_2_8,
            selectionFailureReason = VoiceCallTaggingFallbackReason.MISSING_TOOL_CALL,
        )

        assertEquals("[laughs] 我很好。", taggedReply.toText())
        assertEquals("我很好。", taggedReply.voiceCallSpeechTextOrPlainText())
        assertEquals(
            "(no tag fallback:missing_tool_call)，我很好。",
            taggedReply.voiceCallDisplayTextOrPlainText(),
        )
    }

    private fun assistantMessage(text: String): UIMessage {
        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(text)),
            finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
        )
    }
}
