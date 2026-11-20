package me.rerere.ai.registry

import me.rerere.ai.provider.Modality
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun `DeepSeek Flash accepts image input`() {
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-flash")
        )
    }

    @Test
    fun `DeepSeek Flash compatibility aliases accept image input`() {
        val expected = listOf(Modality.TEXT, Modality.IMAGE)
        assertEquals(expected, ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-flash"))
        assertEquals(expected, ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash"))
        assertEquals(expected, ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash-vision-exp"))
    }
}
