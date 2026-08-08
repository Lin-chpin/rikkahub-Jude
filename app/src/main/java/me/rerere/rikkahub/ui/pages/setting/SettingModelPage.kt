package me.rerere.rikkahub.ui.pages.setting

import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.FileZip
import me.rerere.hugeicons.stroke.Mortarboard01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.Notebook01
import me.rerere.hugeicons.stroke.Tools
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ModelType
import me.rerere.ai.registry.ModelRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingModelPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_model_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DefaultChatModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultVoiceCallAudioTagModelSetting(settings = settings, vm = vm)
            }
            item {
                DefaultTitleModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultSuggestionModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultTranslationModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultOcrModelSetting(settings = settings, vm = vm)
            }

            item {
                DefaultCompressModelSetting(settings = settings, vm = vm)
            }
        }
    }
}

@Composable
private fun DefaultTranslationModelSetting(
    settings: Settings,
    vm: SettingVM
) {
    var showModal by remember { mutableStateOf(false) }
    ModelFeatureCard(
        title = {
            Text(
                stringResource(R.string.setting_model_page_translate_model),
                maxLines = 1
            )
        },
        description = {
            Text(stringResource(R.string.setting_model_page_translate_model_desc))
        },
        icon = {
            Icon(HugeIcons.Earth, null)
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(
                    modelId = settings.translateModeId,
                    type = ModelType.CHAT,
                    onSelect = {
                        vm.updateSettings(
                            settings.copy(
                                translateModeId = it.id
                            )
                        )
                    },
                    providers = settings.providers,
                    modifier = Modifier.wrapContentWidth()
                )
            }
            IconButton(
                onClick = {
                    showModal = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(HugeIcons.Tools, null)
            }
        }
    )

    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = {
                showModal = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormItem(
                    label = {
                        Text(stringResource(R.string.assistant_page_thinking_budget))
                    },
                ) {
                    ReasoningButton(
                        reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                        onUpdateReasoningLevel = {
                            vm.updateSettings(settings.copy(translateThinkingBudget = it.budgetTokens))
                        }
                    )
                }

                FormItem(
                    label = {
                        Text(stringResource(R.string.setting_model_page_prompt))
                    },
                    description = {
                        Text(stringResource(R.string.setting_model_page_translate_prompt_vars))
                    }
                ) {
                    OutlinedTextField(
                        value = settings.translatePrompt,
                        onValueChange = {
                            vm.updateSettings(
                                settings.copy(
                                    translatePrompt = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10,
                    )
                    TextButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(
                                    translatePrompt = DEFAULT_TRANSLATION_PROMPT
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultSuggestionModelSetting(
    settings: Settings,
    vm: SettingVM
) {
    var showModal by remember { mutableStateOf(false) }
    ModelFeatureCard(
        title = {
            Text(
                text = stringResource(R.string.setting_model_page_suggestion_model),
                maxLines = 1
            )
        },
        description = {
            Text(stringResource(R.string.setting_model_page_suggestion_model_desc))
        },
        icon = {
            Icon(HugeIcons.MessageMultiple01, null)
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(
                    modelId = settings.suggestionModelId,
                    type = ModelType.CHAT,
                    onSelect = {
                        vm.updateSettings(
                            settings.copy(
                                suggestionModelId = it.id
                            )
                        )
                    },
                    providers = settings.providers,
                    allowClear = true,
                    modifier = Modifier.wrapContentWidth()
                )
            }
            IconButton(
                onClick = {
                    showModal = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(HugeIcons.Tools, null)
            }
        }
    )

    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = {
                showModal = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormItem(
                    label = {
                        Text(stringResource(R.string.setting_model_page_prompt))
                    },
                    description = {
                        Text(stringResource(R.string.setting_model_page_suggestion_prompt_vars))
                    }
                ) {
                    OutlinedTextField(
                        value = settings.suggestionPrompt,
                        onValueChange = {
                            vm.updateSettings(
                                settings.copy(
                                    suggestionPrompt = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8
                    )
                    TextButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(
                                    suggestionPrompt = DEFAULT_SUGGESTION_PROMPT
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultTitleModelSetting(
    settings: Settings,
    vm: SettingVM
) {
    var showModal by remember { mutableStateOf(false) }
    ModelFeatureCard(
        title = {
            Text(stringResource(R.string.setting_model_page_title_model), maxLines = 1)
        },
        description = {
            Text(stringResource(R.string.setting_model_page_title_model_desc))
        },
        icon = {
            Icon(HugeIcons.Notebook01, null)
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(
                    modelId = settings.titleModelId,
                    type = ModelType.CHAT,
                    onSelect = {
                        vm.updateSettings(
                            settings.copy(
                                titleModelId = it.id
                            )
                        )
                    },
                    providers = settings.providers,
                    allowClear = true,
                    modifier = Modifier.wrapContentWidth()
                )
            }
            IconButton(
                onClick = {
                    showModal = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(HugeIcons.Tools, null)
            }
        }
    )

    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = {
                showModal = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormItem(
                    label = {
                        Text(stringResource(R.string.setting_model_page_prompt))
                    },
                    description = {
                        Text(stringResource(R.string.setting_model_page_suggestion_prompt_vars))
                    }
                ) {
                    OutlinedTextField(
                        value = settings.titlePrompt,
                        onValueChange = {
                            vm.updateSettings(
                                settings.copy(
                                    titlePrompt = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8
                    )
                    TextButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(
                                    titlePrompt = DEFAULT_TITLE_PROMPT
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultChatModelSetting(
    settings: Settings,
    vm: SettingVM
) {
    ModelFeatureCard(
        icon = {
            Icon(HugeIcons.Message01, null)
        },
        title = {
            Text(stringResource(R.string.setting_model_page_chat_model), maxLines = 1)
        },
        description = {
            Text(stringResource(R.string.setting_model_page_chat_model_desc))
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                ModelSelector(
                    modelId = settings.chatModelId,
                    type = ModelType.CHAT,
                    onSelect = {
                        vm.updateSettings(
                            settings.copy(
                                chatModelId = it.id
                            )
                        )
                    },
                    providers = settings.providers,
                    modifier = Modifier.wrapContentWidth()
                )
            }
        }
    )
}

@Composable
private fun DefaultVoiceCallAudioTagModelSetting(
    settings: Settings,
    vm: SettingVM,
) {
    var showModal by remember { mutableStateOf(false) }
    val config = settings.voiceCallAudioTagConfig
    ModelFeatureCard(
        icon = {
            Icon(HugeIcons.MessageMultiple01, null)
        },
        title = {
            Text(stringResource(R.string.setting_model_page_voice_call_audio_tag_model), maxLines = 1)
        },
        description = {
            Text(stringResource(R.string.setting_model_page_voice_call_audio_tag_model_desc))
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                if (config.enabled) {
                    SeparateOpenAIModelSelector(
                        providerName = "语音标签专用 OpenAI API",
                        modelId = config.modelId,
                        apiKey = config.apiKey,
                        baseUrl = config.baseUrl,
                        chatCompletionsPath = config.chatCompletionsPath,
                        useResponseApi = config.useResponseApi,
                        onModelIdChange = { modelId ->
                            vm.updateSettings(
                                settings.copy(
                                    voiceCallAudioTagConfig = config.copy(modelId = modelId)
                                )
                            )
                        },
                        modifier = Modifier.wrapContentWidth()
                    )
                } else {
                    ModelSelector(
                        modelId = settings.voiceCallAudioTagModelId ?: settings.chatModelId,
                        type = ModelType.CHAT,
                        onSelect = { model ->
                            vm.updateSettings(
                                settings.copy(
                                    voiceCallAudioTagModelId = model.modelId.takeIf { it.isNotBlank() }?.let { model.id }
                                )
                            )
                        },
                        providers = settings.providers,
                        allowClear = true,
                        modifier = Modifier.wrapContentWidth()
                    )
                }
            }
            IconButton(
                onClick = {
                    showModal = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(HugeIcons.Tools, null)
            }
        }
    )

    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = {
                showModal = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeparateOpenAIConfigForm(
                    title = "语音标签专用 OpenAI API",
                    description = "开启后，通话中的语音情绪标签会使用这里的 API，不影响当前对话模型 API。",
                    toggleLabel = "单独使用标签 API",
                    enabled = config.enabled,
                    apiKey = config.apiKey,
                    baseUrl = config.baseUrl,
                    chatCompletionsPath = config.chatCompletionsPath,
                    useResponseApi = config.useResponseApi,
                    onEnabledChange = { enabled ->
                        vm.updateSettings(
                            settings.copy(
                                voiceCallAudioTagConfig = config.copy(enabled = enabled)
                            )
                        )
                    },
                    onApiKeyChange = { apiKey ->
                        vm.updateSettings(
                            settings.copy(
                                voiceCallAudioTagConfig = config.copy(apiKey = apiKey)
                            )
                        )
                    },
                    onBaseUrlChange = { baseUrl ->
                        vm.updateSettings(
                            settings.copy(
                                voiceCallAudioTagConfig = config.copy(baseUrl = baseUrl)
                            )
                        )
                    },
                    onChatCompletionsPathChange = { path ->
                        vm.updateSettings(
                            settings.copy(
                                voiceCallAudioTagConfig = config.copy(chatCompletionsPath = path)
                            )
                        )
                    },
                    onUseResponseApiChange = { useResponseApi ->
                        vm.updateSettings(
                            settings.copy(
                                voiceCallAudioTagConfig = config.copy(useResponseApi = useResponseApi)
                            )
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DefaultOcrModelSetting(
    settings: Settings,
    vm: SettingVM
) {
    var showModal by remember { mutableStateOf(false) }
    ModelFeatureCard(
        title = {
            Text(
                stringResource(R.string.setting_model_page_ocr_model),
                maxLines = 1
            )
        },
        description = {
            Text(stringResource(R.string.setting_model_page_ocr_model_desc))
        },
        icon = {
            Icon(HugeIcons.View, null)
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                if (settings.ocrOpenAIConfig.enabled) {
                    OcrOpenAIModelSelector(
                        settings = settings,
                        vm = vm,
                        modifier = Modifier.wrapContentWidth()
                    )
                } else {
                    ModelSelector(
                        modelId = settings.ocrModelId ?: settings.chatModelId,
                        type = ModelType.CHAT,
                        onSelect = { model ->
                            vm.updateSettings(
                                settings.copy(
                                    ocrModelId = model.modelId.takeIf { it.isNotBlank() }?.let { model.id }
                                )
                            )
                        },
                        providers = settings.providers,
                        allowClear = true,
                        modifier = Modifier.wrapContentWidth()
                    )
                }
            }
            IconButton(
                onClick = {
                    showModal = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(HugeIcons.Tools, null)
            }
        }
    )

    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = {
                showModal = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OcrOpenAIConfigForm(settings = settings, vm = vm)

                FormItem(
                    label = {
                        Text(stringResource(R.string.setting_model_page_prompt))
                    },
                    description = {
                        Text(stringResource(R.string.setting_model_page_ocr_prompt_vars))
                    }
                ) {
                    OutlinedTextField(
                        value = settings.ocrPrompt,
                        onValueChange = {
                            vm.updateSettings(
                                settings.copy(
                                    ocrPrompt = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10,
                    )
                    TextButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(
                                    ocrPrompt = DEFAULT_OCR_PROMPT
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
private fun OcrOpenAIModelSelector(
    settings: Settings,
    vm: SettingVM,
    modifier: Modifier = Modifier,
) {
    val config = settings.ocrOpenAIConfig
    SeparateOpenAIModelSelector(
        providerName = "OCR 专用 OpenAI API",
        modelId = config.modelId,
        apiKey = config.apiKey,
        baseUrl = config.baseUrl,
        chatCompletionsPath = config.chatCompletionsPath,
        useResponseApi = config.useResponseApi,
        onModelIdChange = { modelId ->
            vm.updateSettings(
                settings.copy(
                    ocrOpenAIConfig = config.copy(modelId = modelId)
                )
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun OcrOpenAIConfigForm(
    settings: Settings,
    vm: SettingVM
) {
    val config = settings.ocrOpenAIConfig
    SeparateOpenAIConfigForm(
        title = "OCR 专用 OpenAI API",
        description = "开启后，图片 OCR 会使用这里的 OpenAI API，不影响当前对话模型 API。",
        toggleLabel = "单独使用 OCR API",
        enabled = config.enabled,
        apiKey = config.apiKey,
        baseUrl = config.baseUrl,
        chatCompletionsPath = config.chatCompletionsPath,
        useResponseApi = config.useResponseApi,
        onEnabledChange = { enabled ->
            vm.updateSettings(
                settings.copy(ocrOpenAIConfig = config.copy(enabled = enabled))
            )
        },
        onApiKeyChange = { apiKey ->
            vm.updateSettings(
                settings.copy(ocrOpenAIConfig = config.copy(apiKey = apiKey))
            )
        },
        onBaseUrlChange = { baseUrl ->
            vm.updateSettings(
                settings.copy(ocrOpenAIConfig = config.copy(baseUrl = baseUrl))
            )
        },
        onChatCompletionsPathChange = { path ->
            vm.updateSettings(
                settings.copy(ocrOpenAIConfig = config.copy(chatCompletionsPath = path))
            )
        },
        onUseResponseApiChange = { useResponseApi ->
            vm.updateSettings(
                settings.copy(ocrOpenAIConfig = config.copy(useResponseApi = useResponseApi))
            )
        },
    )
}

@Composable
private fun DefaultCompressModelSetting(
    settings: Settings,
    vm: SettingVM
) {
    var showModal by remember { mutableStateOf(false) }
    ModelFeatureCard(
        title = {
            Text(
                stringResource(R.string.setting_model_page_compress_model),
                maxLines = 1
            )
        },
        description = {
            Text(stringResource(R.string.setting_model_page_compress_model_desc))
        },
        icon = {
            Icon(HugeIcons.FileZip, null)
        },
        actions = {
            Box(modifier = Modifier.weight(1f)) {
                if (settings.compressOpenAIConfig.enabled) {
                    CompressOpenAIModelSelector(
                        settings = settings,
                        vm = vm,
                        modifier = Modifier.wrapContentWidth()
                    )
                } else {
                    ModelSelector(
                        modelId = settings.compressModelId ?: settings.chatModelId,
                        type = ModelType.CHAT,
                        onSelect = { model ->
                            vm.updateSettings(
                                settings.copy(
                                    compressModelId = model.modelId.takeIf { it.isNotBlank() }?.let { model.id }
                                )
                            )
                        },
                        providers = settings.providers,
                        allowClear = true,
                        modifier = Modifier.wrapContentWidth()
                    )
                }
            }
            IconButton(
                onClick = {
                    showModal = true
                },
                colors = IconButtonDefaults.filledTonalIconButtonColors()
            ) {
                Icon(HugeIcons.Tools, null)
            }
        }
    )

    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = {
                showModal = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompressOpenAIConfigForm(settings = settings, vm = vm)

                FormItem(
                    label = {
                        Text(stringResource(R.string.setting_model_page_prompt))
                    },
                    description = {
                        Text(stringResource(R.string.setting_model_page_compress_prompt_vars))
                    }
                ) {
                    OutlinedTextField(
                        value = settings.compressPrompt,
                        onValueChange = {
                            vm.updateSettings(
                                settings.copy(
                                    compressPrompt = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10,
                    )
                    TextButton(
                        onClick = {
                            vm.updateSettings(
                                settings.copy(
                                    compressPrompt = DEFAULT_COMPRESS_PROMPT
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.setting_model_page_reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompressOpenAIModelSelector(
    settings: Settings,
    vm: SettingVM,
    modifier: Modifier = Modifier,
) {
    val config = settings.compressOpenAIConfig
    SeparateOpenAIModelSelector(
        providerName = "压缩专用 OpenAI API",
        modelId = config.modelId,
        apiKey = config.apiKey,
        baseUrl = config.baseUrl,
        chatCompletionsPath = config.chatCompletionsPath,
        useResponseApi = config.useResponseApi,
        onModelIdChange = { modelId ->
            vm.updateSettings(
                settings.copy(
                    compressOpenAIConfig = config.copy(modelId = modelId)
                )
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun CompressOpenAIConfigForm(
    settings: Settings,
    vm: SettingVM
) {
    val config = settings.compressOpenAIConfig
    SeparateOpenAIConfigForm(
        title = "压缩专用 OpenAI API",
        description = "开启后，滚动摘要和手动压缩会使用这里的 OpenAI API，不影响当前对话模型 API。",
        toggleLabel = "单独使用压缩 API",
        enabled = config.enabled,
        apiKey = config.apiKey,
        baseUrl = config.baseUrl,
        chatCompletionsPath = config.chatCompletionsPath,
        useResponseApi = config.useResponseApi,
        onEnabledChange = { enabled ->
            vm.updateSettings(
                settings.copy(compressOpenAIConfig = config.copy(enabled = enabled))
            )
        },
        onApiKeyChange = { apiKey ->
            vm.updateSettings(
                settings.copy(compressOpenAIConfig = config.copy(apiKey = apiKey))
            )
        },
        onBaseUrlChange = { baseUrl ->
            vm.updateSettings(
                settings.copy(compressOpenAIConfig = config.copy(baseUrl = baseUrl))
            )
        },
        onChatCompletionsPathChange = { path ->
            vm.updateSettings(
                settings.copy(compressOpenAIConfig = config.copy(chatCompletionsPath = path))
            )
        },
        onUseResponseApiChange = { useResponseApi ->
            vm.updateSettings(
                settings.copy(compressOpenAIConfig = config.copy(useResponseApi = useResponseApi))
            )
        },
    )
}


@Composable
private fun SeparateOpenAIModelSelector(
    providerName: String,
    modelId: String,
    apiKey: String,
    baseUrl: String,
    chatCompletionsPath: String,
    useResponseApi: Boolean,
    onModelIdChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val providerManager = koinInject<ProviderManager>()
    val provider = remember(apiKey, baseUrl, chatCompletionsPath, useResponseApi) {
        ProviderSetting.OpenAI(
            name = providerName,
            apiKey = apiKey,
            baseUrl = baseUrl.trimEnd('/'),
            chatCompletionsPath = chatCompletionsPath,
            useResponseApi = useResponseApi,
        )
    }
    val availableModels by produceState<List<Model>>(emptyList(), provider) {
        runCatching {
            value = providerManager.getProviderByType(provider)
                .listModels(provider)
                .map { model ->
                    model.copy(
                        type = ModelType.CHAT,
                        inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId),
                        outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId),
                        abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId),
                    )
                }
                .sortedBy { it.modelId }
        }.onFailure {
            it.printStackTrace()
        }
    }
    val selectedFallback = remember(modelId, availableModels) {
        modelId
            .takeIf { it.isNotBlank() && availableModels.none { model -> model.modelId == it } }
            ?.let { Model(modelId = it, displayName = it, type = ModelType.CHAT) }
    }
    val models = remember(availableModels, selectedFallback) {
        if (selectedFallback == null) {
            availableModels
        } else {
            listOf(selectedFallback) + availableModels
        }
    }
    val selectedModelId = remember(modelId, models) {
        models.firstOrNull { it.modelId == modelId }?.id
    }

    ModelSelector(
        modelId = selectedModelId,
        type = ModelType.CHAT,
        onSelect = { model -> onModelIdChange(model.modelId) },
        providers = listOf(provider.copy(models = models)),
        modifier = modifier
    )
}

@Composable
private fun SeparateOpenAIConfigForm(
    title: String,
    description: String,
    toggleLabel: String,
    enabled: Boolean,
    apiKey: String,
    baseUrl: String,
    chatCompletionsPath: String,
    useResponseApi: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onChatCompletionsPathChange: (String) -> Unit,
    onUseResponseApiChange: (Boolean) -> Unit,
) {
    FormItem(
        label = {
            Text(title)
        },
        description = {
            Text(description)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(toggleLabel)
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }

        if (enabled) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                )
                OutlinedTextField(
                    value = chatCompletionsPath,
                    onValueChange = onChatCompletionsPathChange,
                    label = { Text("Chat Completions Path") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("使用 Responses API")
                    Switch(
                        checked = useResponseApi,
                        onCheckedChange = onUseResponseApiChange
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelFeatureCard(
    modifier: Modifier = Modifier,
    description: @Composable () -> Unit = {},
    icon: @Composable () -> Unit,
    title: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                        title()
                    }
                    ProvideTextStyle(
                        MaterialTheme.typography.bodySmall.copy(
                            color = LocalContentColor.current.copy(alpha = 0.6f)
                        )
                    ) {
                        description()
                    }
                }
                Box(
                    modifier = Modifier
                        .size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions()
            }
        }
    }
}
