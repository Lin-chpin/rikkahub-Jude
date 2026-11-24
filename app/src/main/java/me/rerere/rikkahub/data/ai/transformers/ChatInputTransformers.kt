package me.rerere.rikkahub.data.ai.transformers

internal val chatInputTransformers: List<InputMessageTransformer> by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}
