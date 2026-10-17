package me.rerere.rikkahub.ui.pages.assistant.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPromptRecoveryTest {
    @Test
    fun normalPromptsRemainEditableAndUnsafeTextNeverNeedsFullLayout() {
        assertFalse(needsAssistantPromptRecovery(""))
        assertFalse(needsAssistantPromptRecovery("正常人设\n换行\r\n\t缩进，保留 emoji 😊".repeat(20)))
        assertFalse(needsAssistantPromptRecovery("a".repeat(MAX_RENDERED_ASSISTANT_PROMPT_CHARS)))
        assertTrue(needsAssistantPromptRecovery("a".repeat(MAX_RENDERED_ASSISTANT_PROMPT_CHARS + 1)))
        assertTrue(needsAssistantPromptRecovery("导入后的文字\u0000乱码"))
        assertTrue(needsAssistantPromptRecovery("\u0001binary\u007f"))
    }
}
