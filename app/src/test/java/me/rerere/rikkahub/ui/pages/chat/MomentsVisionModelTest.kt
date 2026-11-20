package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentsVisionModelTest {
    @Test
    fun `registered DeepSeek Flash names are accepted as OCR fallback with stale text metadata`() {
        listOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp").forEach { modelId ->
            val model = Model(modelId = modelId)
            val settings = Settings.dummy().copy(
                providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
                ocrModelId = model.id,
            )

            assertTrue("$modelId should support image input", settings.hasConfiguredVisionOcrModel())
        }
    }

    @Test
    fun `unregistered text model remains non vision OCR model`() {
        val textOnly = Model(modelId = "custom-text-only")
        val settings = Settings.dummy().copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(textOnly))),
            ocrModelId = textOnly.id,
        )

        assertFalse(settings.hasConfiguredVisionOcrModel())
    }
}
