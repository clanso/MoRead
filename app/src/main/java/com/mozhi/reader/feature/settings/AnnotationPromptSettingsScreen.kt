package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mozhi.reader.R
import com.mozhi.reader.core.i18n.resolve
import com.mozhi.reader.ai.companion.AnnotationTemplatePreview
import com.mozhi.reader.core.datastore.AnnotationPromptTemplates
import com.mozhi.reader.core.datastore.GlobalPromptPreset
import com.mozhi.reader.ui.components.DashedAddRow
import com.mozhi.reader.ui.components.MoReadRow
import com.mozhi.reader.ui.components.MoReadRowDivider
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import com.mozhi.reader.ui.components.MoReadSection
import com.mozhi.reader.ui.components.MoReadTextField

@Composable
fun AnnotationPromptSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<GlobalPromptPreset?>(null) }
    var reset by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<String?>(null) }
    var resetTemplates by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            snackbar.showSnackbar(when (event) {
                is SettingsEvent.ShowMessage -> event.message
                is SettingsEvent.ShowText -> event.text.resolve(context)
            })
        }
    }
    Box(Modifier.fillMaxSize()) {
        MoReadSecondaryPage(title = "主动段评提示词", onBack = onBack) {
            if (!state.isLoaded) return@MoReadSecondaryPage
            item {
                Text("只用于自动随读段评。可添加多条预设，分别开关、编辑并选择注入位置；按列表顺序注入。角色设定、原文定位和防剧透规则仍会生效。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.autonomy.annotationPrompts, key = GlobalPromptPreset::id) { preset ->
                PresetRow(preset, { viewModel.setAnnotationPromptEnabled(preset.id, it) },
                    { editing = preset }, { viewModel.deleteAnnotationPrompt(preset.id) })
            }
            item {
                DashedAddRow("添加段评预设", onClick = {
                    editing = GlobalPromptPreset("", "", "", enabled = true)
                })
            }
            item { TextButton(onClick = { reset = true }) { Text("恢复默认段评提示词") } }
            item {
                MoReadSection(title = stringResource(R.string.annotation_templates_section),
                    footer = stringResource(R.string.annotation_templates_footer)) {
                    AnnotationPromptTemplates.ALL.forEachIndexed { index, template ->
                        if (index > 0) MoReadRowDivider()
                        val custom = state.autonomy.annotationTemplates.isCustom(template.id)
                        MoReadRow(title = if (custom) stringResource(R.string.annotation_template_modified, template.name) else template.name,
                            subtitle = template.summary, onClick = { editingTemplate = template.id })
                    }
                }
            }
            item { TextButton(onClick = { resetTemplates = true }) { Text(stringResource(R.string.annotation_templates_reset_all)) } }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(20.dp))
    }
    editing?.let { preset ->
        PresetEditorDialog(preset, { editing = null }, {
            viewModel.saveAnnotationPrompt(it)
            editing = null
        }, kindLabel = "段评预设")
    }
    editingTemplate?.let { id ->
        AnnotationTemplateEditor(
            id = id,
            current = state.autonomy.annotationTemplates.text(id),
            onDismiss = { editingTemplate = null },
            onSave = { text -> viewModel.saveAnnotationTemplate(id, text); editingTemplate = null },
            onRestore = { viewModel.saveAnnotationTemplate(id, null); editingTemplate = null }
        )
    }
    if (resetTemplates) AlertDialog(onDismissRequest = { resetTemplates = false },
        title = { Text(stringResource(R.string.annotation_templates_reset_title)) },
        text = { Text(stringResource(R.string.annotation_templates_reset_message)) },
        confirmButton = {
            TextButton(onClick = { viewModel.resetAnnotationTemplates(); resetTemplates = false }) {
                Text(stringResource(R.string.action_restore_default))
            }
        },
        dismissButton = { TextButton(onClick = { resetTemplates = false }) { Text(stringResource(R.string.action_cancel)) } })
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("恢复默认提示词？") },
        text = { Text("将用两条内置预设替换当前所有段评预设。") },
        confirmButton = { TextButton(onClick = { viewModel.resetAnnotationPrompts(); reset = false }) { Text("恢复默认") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}

/** 一段流程提示词的编辑框：全文可改；下面只读地列出占位符和应用自动追加的内容。 */
@Composable
private fun AnnotationTemplateEditor(
    id: String,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRestore: () -> Unit
) {
    val template = AnnotationPromptTemplates.template(id) ?: return
    var text by rememberSaveable(id) { mutableStateOf(current) }
    val appended = remember(id) { AnnotationTemplatePreview.appended(id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        title = { Text(template.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(template.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoReadTextField(value = text, onValueChange = { text = it.take(AnnotationPromptTemplates.MAX_CHARS) },
                    label = stringResource(R.string.annotation_template_full_text), singleLine = false, minLines = 8,
                    supporting = stringResource(R.string.annotation_template_length, text.length, AnnotationPromptTemplates.MAX_CHARS))
                if (template.placeholders.isNotBlank()) {
                    Text(stringResource(R.string.annotation_template_placeholders), style = MaterialTheme.typography.labelMedium)
                    Text(template.placeholders, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (appended.isNotBlank()) {
                    Text(stringResource(R.string.annotation_template_appended), style = MaterialTheme.typography.labelMedium)
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(appended, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRestore) { Text(stringResource(R.string.action_restore_default)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    )
}
