package me.rerere.rikkahub.ui.pages.extensions

import me.rerere.rikkahub.data.model.Lorebook
import kotlin.uuid.Uuid

internal fun reorderLorebooks(
    lorebooks: List<Lorebook>,
    fromKey: Any,
    toKey: Any,
): List<Lorebook>? {
    val from = lorebooks.indexOfFirst { it.id.toString() == fromKey.toString() }
    val to = lorebooks.indexOfFirst { it.id.toString() == toKey.toString() }
    if (from < 0 || to < 0 || from == to) return null

    val folderId = lorebooks[from].folderId
    if (folderId != lorebooks[to].folderId) return null

    val positions = lorebooks.indices.filter { lorebooks[it].folderId == folderId }
    val group = positions.map(lorebooks::get).toMutableList()
    val fromGroup = positions.indexOf(from)
    val toGroup = positions.indexOf(to)
    group.add(toGroup, group.removeAt(fromGroup))

    var groupIndex = 0
    return lorebooks.map { book ->
        if (book.folderId == folderId) group[groupIndex++] else book
    }
}

internal fun assignLorebooksToFolder(
    lorebooks: List<Lorebook>,
    selectedIds: Set<String>,
    folderId: Uuid?,
): List<Lorebook> = lorebooks.map { book ->
    if (book.id.toString() in selectedIds) book.copy(folderId = folderId) else book
}
