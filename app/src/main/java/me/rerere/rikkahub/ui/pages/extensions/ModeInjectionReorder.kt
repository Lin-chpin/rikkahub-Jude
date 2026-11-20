package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.lazy.LazyListItemInfo
import me.rerere.rikkahub.data.model.PromptInjection

internal fun reorderModeInjections(
    injections: List<PromptInjection.ModeInjection>,
    from: LazyListItemInfo,
    to: LazyListItemInfo,
): List<PromptInjection.ModeInjection>? {
    // 固定置顶卡片也计入 LazyColumn 下标；按稳定 key 在当前数据中定位。
    val fromIndex = injections.indexOfFirst { it.id == from.key }
    val toIndex = injections.indexOfFirst { it.id == to.key }
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return null

    return injections.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}
