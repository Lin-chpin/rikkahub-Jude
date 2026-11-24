package me.rerere.ai.registry

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ModelAbility
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

    @Test
    fun `GPT 6 series models accept image input and reasoning tools`() {
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("gpt-6.2-astra"),
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("gpt-6.2-astra"),
        )
        assertEquals(
            setOf(BuiltInTools.ImageGeneration),
            ModelRegistry.MODEL_BUILT_IN_TOOLS.getData("gpt-6.2-astra"),
        )
        assertEquals(
            emptySet<BuiltInTools>(),
            ModelRegistry.MODEL_BUILT_IN_TOOLS.getData("gpt-6.3-astra"),
        )
    }
}
