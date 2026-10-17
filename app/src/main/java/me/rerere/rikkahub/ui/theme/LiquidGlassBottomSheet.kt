@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package me.rerere.rikkahub.ui.theme

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ModalBottomSheet as MaterialModalBottomSheet

/** Keeps the native sheet state, dismissal, gestures, keyboard and insets intact. */
@Composable
fun LiquidGlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.modalWindowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState, sheetMaxWidth = sheetMaxWidth, sheetGesturesEnabled = sheetGesturesEnabled,
        shape = shape, containerColor = containerColor,
        contentColor = contentColor, tonalElevation = tonalElevation, scrimColor = scrimColor,
        dragHandle = dragHandle, contentWindowInsets = contentWindowInsets, properties = properties, content = content,
    )
}
