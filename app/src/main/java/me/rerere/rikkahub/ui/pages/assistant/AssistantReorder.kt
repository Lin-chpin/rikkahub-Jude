package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.foundation.lazy.LazyListItemInfo
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Tag

/**
 * Reorders [assistants] by resolving [from] and [to] item keys against assistant IDs,
 * returning null when either endpoint is missing or the position is unchanged.
 */
internal fun reorderAssistants(
    assistants: List<Assistant>,
    from: LazyListItemInfo,
    to: LazyListItemInfo,
): List<Assistant>? = reorderByKey(assistants, from, to) { it.id }

/**
 * Reorders [tags] by resolving [from] and [to] item keys against tag IDs,
 * returning null when either endpoint is missing or the position is unchanged.
 */
internal fun reorderAssistantTags(
    tags: List<Tag>,
    from: LazyListItemInfo,
    to: LazyListItemInfo,
): List<Tag>? = reorderByKey(tags, from, to) { it.id }

private inline fun <T> reorderByKey(
    items: List<T>,
    from: LazyListItemInfo,
    to: LazyListItemInfo,
    keySelector: (T) -> Any,
): List<T>? {
    val fromIndex = items.indexOfFirst { keySelector(it) == from.key }
    val toIndex = items.indexOfFirst { keySelector(it) == to.key }
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return null

    return items.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}
