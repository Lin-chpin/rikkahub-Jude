package me.rerere.rikkahub.personal.heartbeat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import me.rerere.rikkahub.R
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun HeartbeatExpandableSection(title: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(if (expanded) R.string.heartbeat_section_expanded else R.string.heartbeat_section_collapsed)
    HeartbeatGlassSection {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = description }) {
            Text(title + if (expanded) " ▴" else " ▾", style = MaterialTheme.typography.titleMedium)
        }
        if (expanded) content()
    }
}

@Composable
internal fun NumberField(label: String, value: Int, onValueChange: (Int) -> Unit, hint: String = "") {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText -> text = newText; newText.toIntOrNull()?.let(onValueChange) },
        label = { Text(label) },
        supportingText = if (hint.isNotEmpty()) { { Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant) } } else null,
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(18.dp),
    )
}
