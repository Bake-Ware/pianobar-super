package org.pianobarsuper.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.pianobarsuper.app.net.title

/**
 * The player sometimes asks a question (a station name, a choice from a
 * numbered list, yes/no). Numbered choices and single keys become buttons;
 * anything else can be typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptDialog() {
    val repo = repo()
    val snapshot by repo.snapshot.collectAsStateWithLifecycle()
    val current = snapshot ?: return
    val prompt = current.prompt
    if (!prompt.active) return
    var answer by remember(prompt.id) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    if (prompt.kind == "delete_station") {
        AlertDialog(
            onDismissRequest = { repo.answer("n", prompt.id) },
            title = { Text("Delete station?") },
            text = { Text("Are you sure you want to delete “${prompt.station}”?") },
            confirmButton = { TextButton(onClick = { repo.answer("y", prompt.id) }) { Text("Delete station", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { repo.answer("n", prompt.id) }) { Text("Cancel") } },
        )
        return
    }
    val output = current.output
    val questionStart = output.lastIndexOf("[?]")
    val previous = if (questionStart > 0) output.lastIndexOf("[?]", questionStart - 1) else -1
    val context = output.substring(maxOf(previous + 3, output.length - 7000, 0))
    val lastLine = output.trim().lines().lastOrNull()?.replace(Regex("^\\[\\?]\\s*"), "")?.ifEmpty { null } ?: "Your player needs an answer."
    val choices = mutableListOf<Pair<String, String>>()
    if (!prompt.secret && prompt.line) {
        Regex("^\\s*(\\d+)\\)\\s+(.+)$", RegexOption.MULTILINE).findAll(context).forEach { choices += it.groupValues[1] to "${it.groupValues[1]} · ${it.groupValues[2]}" }
    }
    if (!prompt.secret && !prompt.line) {
        val mask = prompt.mask
        val keys = if (mask.isNotEmpty()) mask.lowercase().toSet().map { it.toString() }
            else if ("What to do" in lastLine) listOf("+", "-", "t", "b", "i", "?") else emptyList()
        for (key in keys) {
            val action = current.state.actions.firstOrNull { it.key == key }
            choices += key to when {
                "yn" in mask.lowercase() -> if (key == "y") "Yes" else "No"
                action != null && mask.isEmpty() -> action.title()
                else -> key.uppercase()
            }
        }
    }
    LaunchedEffect(prompt.id) { if (choices.isEmpty()) try { focus.requestFocus() } catch (e: Exception) { } }
    fun submit() { repo.answer(answer, prompt.id) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (prompt.secret) "Welcome back." else "Make your selection.") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                    Text(context.trim().takeLast(1500), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState(), reverseScrolling = true).fillMaxWidth())
                }
                if (choices.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.forEach { (value, label) -> OutlinedButton(onClick = { repo.answer(value, prompt.id) }) { Text(label, maxLines = 1) } }
                }
                OutlinedTextField(answer, { if (it.length <= prompt.limit) answer = it }, Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text(lastLine, maxLines = 2) }, singleLine = true,
                    visualTransformation = if (prompt.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (prompt.secret) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }))
            }
        },
        confirmButton = { TextButton(onClick = ::submit) { Text("Continue") } },
        dismissButton = if (!prompt.secret && prompt.line) ({ TextButton(onClick = { repo.answer("", prompt.id) }) { Text("Cancel") } }) else null,
    )
}
