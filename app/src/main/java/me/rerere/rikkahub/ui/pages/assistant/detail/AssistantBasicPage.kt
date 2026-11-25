package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.DeferredSliderState
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.TagsInput
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.rememberDeferredSliderState
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import me.rerere.rikkahub.data.model.Tag as DataTag

@Composable
fun AssistantBasicPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_basic))
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
    ) { innerPadding ->
        AssistantBasicContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            providers = providers,
            tags = tags,
            onUpdate = { vm.update(it, assistant) },
            vm = vm
        )
    }
}

@Composable
internal fun AssistantBasicContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    providers: List<me.rerere.ai.provider.ProviderSetting>,
    tags: List<DataTag>,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = { avatar ->
                    onUpdate(
                        assistant.copy(
                            avatar = avatar
                        )
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .heroAnimation("assistant_${assistant.id}")
            )
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_name))
                },
                modifier = Modifier.padding(8.dp),

            ) {
                OutlinedTextField(
                    value = assistant.name,
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                name = it
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_tags))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                TagsInput(
                    value = assistant.tags,
                    tags = tags,
                    onValueChange = { tagIds, tagList ->
                        vm.updateTags(tagIds, tagList)
                    },
                )
            }

            HorizontalDivider()

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useAssistantAvatar,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    useAssistantAvatar = it
                                )
                            )
                        }
                    )
                }
            )

            HorizontalDivider()

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_moments_chat_style))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_moments_chat_style_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.momentsChatStyle,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    momentsChatStyle = it
                                )
                            )
                        }
                    )
                }
            )

        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_chat_model))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_chat_model_desc))
                },
                content = {
                    ModelSelector(
                        modelId = assistant.chatModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        onSelect = {
                            onUpdate(
                                assistant.copy(
                                    chatModelId = it.id
                                )
                            )
                        },
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_temperature))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_temperature_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.temperature != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    temperature = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                if (assistant.temperature != null) {
                    var temperatureInput by remember(assistant.id) {
                        mutableStateOf(assistant.temperature.toString())
                    }
                    val temperatureValue = temperatureInput.toFloatOrNull()
                    OutlinedTextField(
                        value = temperatureInput,
                        onValueChange = { value ->
                            temperatureInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..2f }?.let { temperature ->
                                onUpdate(
                                    assistant.copy(
                                        temperature = temperature
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = temperatureValue == null || temperatureValue !in 0f..2f,
                        supportingText = {
                            Text("0 - 2")
                        }
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_top_p))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_top_p_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.topP != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    topP = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                assistant.topP?.let { topP ->
                    var topPInput by remember(assistant.id) {
                        mutableStateOf(topP.toString())
                    }
                    val topPValue = topPInput.toFloatOrNull()
                    OutlinedTextField(
                        value = topPInput,
                        onValueChange = { value ->
                            topPInput = value
                            value.toFloatOrNull()?.takeIf { it in 0f..1f }?.let { nextTopP ->
                                onUpdate(
                                    assistant.copy(
                                        topP = nextTopP
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = topPValue == null || topPValue !in 0f..1f,
                        supportingText = {
                            Text("0 - 1")
                        }
                    )
                }
            }
            HorizontalDivider()
            val contextSliderState = rememberDeferredSliderState(
                key = assistant.id,
                persistedValue = clampContextMessageSize(assistant.contextMessageSize),
                normalizeFloat = ::normalizeContextMessageSliderValue,
                toFloat = Int::toFloat,
                clampFloat = { value ->
                    if (value.isNaN()) {
                        DEFAULT_CONTEXT_MESSAGE_SIZE.toFloat()
                    } else {
                        value.coerceIn(CONTEXT_MESSAGE_MIN.toFloat(), CONTEXT_MESSAGE_MAX.toFloat())
                    }
                },
                onPendingFlush = { size ->
                    onUpdate(assistant.copy(contextMessageSize = size))
                },
            )
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_context_message_size))
                },
                description = {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_desc),
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.contextMessageLimitEnabled,
                        onCheckedChange = { enabled ->
                            val nextSize = resolveContextMessageLimitToggle(
                                enabled = enabled,
                                persistedSize = assistant.contextMessageSize,
                                transientSliderValue = if (contextSliderState.isDragging) {
                                    contextSliderState.displayValue
                                } else {
                                    null
                                },
                                pendingCommittedSize = contextSliderState.pendingCommit,
                            )
                            contextSliderState.cancelDrag(
                                pendingValue = if (nextSize > 0) clampContextMessageSize(nextSize) else null
                            )
                            onUpdate(
                                assistant.copy(
                                    contextMessageLimitEnabled = enabled,
                                    contextMessageSize = nextSize,
                                )
                            )
                        },
                    )
                },
            ) {
                if (assistant.contextMessageLimitEnabled) {
                    Slider(
                        value = contextSliderState.sliderValue,
                        onValueChange = contextSliderState::onValueChange,
                        onValueChangeFinished = {
                            contextSliderState.onValueChangeFinished { size ->
                                onUpdate(
                                    assistant.copy(
                                        contextMessageSize = size
                                    )
                                )
                            }
                        },
                        valueRange = CONTEXT_MESSAGE_MIN.toFloat()..CONTEXT_MESSAGE_MAX.toFloat(),
                        steps = 0,
                        modifier = Modifier.fillMaxWidth()
                    )

                    ContextMessageCountLabel(sliderState = contextSliderState)
                } else {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_unlimited),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                    )
                }
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_stream_output))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_stream_output_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.streamOutput,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    streamOutput = it
                                )
                            )
                        }
                    )
                }
            )
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_thinking_budget))
                },
            ) {
                ReasoningButton(
                    reasoningLevel = assistant.reasoningLevel,
                    onUpdateReasoningLevel = { level ->
                        onUpdate(assistant.copy(reasoningLevel = level))
                    }
                )
            }
            HorizontalDivider()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_max_tokens))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_max_tokens_desc))
                }
            ) {
                OutlinedTextField(
                    value = assistant.maxTokens?.toString() ?: "",
                    onValueChange = { text ->
                        val tokens = if (text.isBlank()) {
                            null
                        } else {
                            text.toIntOrNull()?.takeIf { it > 0 }
                        }
                        onUpdate(
                            assistant.copy(
                                maxTokens = tokens
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_max_tokens_no_limit))
                    },
                    supportingText = {
                        if (assistant.maxTokens != null) {
                            Text(stringResource(R.string.assistant_page_max_tokens_limit, assistant.maxTokens))
                        } else {
                            Text(stringResource(R.string.assistant_page_max_tokens_no_token_limit))
                        }
                    }
                )
            }
        }

        Card(
            colors = CustomColors.cardColorsOnSurfaceContainer
        ) {
            val backgroundOpacityState = rememberDeferredSliderState(
                key = assistant.id,
                persistedValue = normalizeOpacity(assistant.backgroundOpacity),
                normalizeFloat = ::normalizeOpacity,
                toFloat = { it },
                clampFloat = { if (it.isNaN()) 1.0f else it.coerceIn(0f, 1f) },
                onPendingFlush = { opacity ->
                    onUpdate(assistant.copy(backgroundOpacity = opacity))
                },
            )
            BackgroundPicker(
                modifier = Modifier.padding(8.dp),
                background = assistant.background,
                backgroundOpacityProvider = { backgroundOpacityState.displayValue },
                onUpdate = { background ->
                    onUpdate(
                        assistant.copy(
                            background = background
                        )
                    )
                }
            )

            if (assistant.background != null) {
                HorizontalDivider()
                BackgroundOpacityFormItem(
                    descriptionResId = R.string.assistant_page_background_opacity_desc,
                    opacityState = backgroundOpacityState,
                    onCommit = { opacity ->
                        onUpdate(
                            assistant.copy(
                                backgroundOpacity = opacity
                            )
                        )
                    }
                )
            }

            HorizontalDivider()
            val voiceCallBackgroundOpacityState = rememberDeferredSliderState(
                key = assistant.id,
                persistedValue = normalizeOpacity(assistant.voiceCallBackgroundOpacity),
                normalizeFloat = ::normalizeOpacity,
                toFloat = { it },
                clampFloat = { if (it.isNaN()) 1.0f else it.coerceIn(0f, 1f) },
                onPendingFlush = { opacity ->
                    onUpdate(assistant.copy(voiceCallBackgroundOpacity = opacity))
                },
            )
            BackgroundPicker(
                modifier = Modifier.padding(8.dp),
                background = assistant.voiceCallBackground,
                backgroundOpacityProvider = { voiceCallBackgroundOpacityState.displayValue },
                labelResId = R.string.assistant_page_voice_call_background,
                descriptionResId = R.string.assistant_page_voice_call_background_desc,
                onUpdate = { background ->
                    onUpdate(
                        assistant.copy(
                            voiceCallBackground = background
                        )
                    )
                }
            )

            if (assistant.voiceCallBackground != null) {
                HorizontalDivider()
                BackgroundOpacityFormItem(
                    descriptionResId = R.string.assistant_page_voice_call_background_opacity_desc,
                    opacityState = voiceCallBackgroundOpacityState,
                    onCommit = { opacity ->
                        onUpdate(
                            assistant.copy(
                                voiceCallBackgroundOpacity = opacity
                            )
                        )
                    }
                )
            }
        }
    }
}

internal const val CONTEXT_MESSAGE_MIN = 1
internal const val CONTEXT_MESSAGE_MAX = 512
internal const val DEFAULT_CONTEXT_MESSAGE_SIZE = 80

internal fun clampContextMessageSize(size: Int): Int =
    size.coerceIn(CONTEXT_MESSAGE_MIN, CONTEXT_MESSAGE_MAX)

internal fun normalizeContextMessageSliderValue(value: Float): Int =
    if (value.isNaN()) {
        DEFAULT_CONTEXT_MESSAGE_SIZE
    } else {
        value.roundToInt().coerceIn(CONTEXT_MESSAGE_MIN, CONTEXT_MESSAGE_MAX)
    }

internal fun normalizeOpacity(value: Float): Float =
    if (value.isNaN()) {
        1.0f
    } else {
        (value.coerceIn(0f, 1f) * 100f).roundToInt() / 100f
    }

internal fun resolveContextMessageLimitToggle(
    enabled: Boolean,
    persistedSize: Int,
    transientSliderValue: Int?,
    pendingCommittedSize: Int?,
): Int {
    val currentSize = transientSliderValue ?: pendingCommittedSize ?: persistedSize
    return if (enabled) {
        if (currentSize <= 0) {
            DEFAULT_CONTEXT_MESSAGE_SIZE
        } else {
            clampContextMessageSize(currentSize)
        }
    } else {
        currentSize
    }
}

@Composable
private fun ContextMessageCountLabel(
    sliderState: DeferredSliderState<Int>,
) {
    Text(
        text = stringResource(
            R.string.assistant_page_context_message_count,
            sliderState.displayValue
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
    )
}

@Composable
private fun BackgroundOpacityFormItem(
    descriptionResId: Int,
    opacityState: DeferredSliderState<Float>,
    onCommit: (Float) -> Unit,
) {
    FormItem(
        modifier = Modifier.padding(8.dp),
        label = {
            Text(stringResource(R.string.assistant_page_background_opacity))
        },
        description = {
            Text(stringResource(descriptionResId))
        }
    ) {
        Slider(
            value = opacityState.sliderValue,
            onValueChange = opacityState::onValueChange,
            onValueChangeFinished = {
                opacityState.onValueChangeFinished(onCommit)
            },
            valueRange = 0f..1f,
            steps = 19,
            modifier = Modifier.fillMaxWidth()
        )
        BackgroundOpacityValueLabel(opacityState = opacityState)
    }
}

@Composable
private fun BackgroundOpacityValueLabel(
    opacityState: DeferredSliderState<Float>,
) {
    Text(
        text = stringResource(
            R.string.assistant_page_background_opacity_value,
            (opacityState.displayValue * 100).roundToInt()
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
    )
}
