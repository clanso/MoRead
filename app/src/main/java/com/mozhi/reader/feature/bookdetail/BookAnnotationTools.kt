package com.mozhi.reader.feature.bookdetail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.R
import com.mozhi.reader.core.i18n.resolve
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadButton
import com.mozhi.reader.ui.components.MoReadButtonStyle
import com.mozhi.reader.ui.components.asString
import com.mozhi.reader.ui.theme.moReadMetrics
import java.text.NumberFormat

/**
 * 详情页「整本段评」卡：后台整本生成的开始 / 停止 / 进度，批注包导出与导入。
 * 消息交给详情页的 snackbar（[onMessage]）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookAnnotationToolsCard(
    bookTitle: String,
    onMessage: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookAnnotationToolsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latestMessage by rememberUpdatedState(onMessage)
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.events.collect { latestMessage(it.resolve(context)) } }
    var starting by rememberSaveable { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        viewModel.exportPack(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.importPack(uri)
    }
    FrostedSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(moReadMetrics().radiusFor(24)),
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.whole_book_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            when (state.phase) {
                WholeBookAnnotationState.Phase.RUNNING -> {
                    val chapter = when {
                        state.chapterIndex < 0 -> stringResource(R.string.whole_book_preparing)
                        state.chapterTitle.isBlank() -> stringResource(R.string.whole_book_chapter, state.chapterIndex + 1)
                        else -> stringResource(R.string.whole_book_chapter_titled, state.chapterIndex + 1, state.chapterTitle)
                    }
                    Text(
                        if (state.total > 0) stringResource(R.string.whole_book_running, chapter, state.done + 1, state.total, state.created)
                        else stringResource(R.string.whole_book_running_no_total, chapter, state.created),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.total > 0) LinearProgressIndicator(
                        progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    ) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                WholeBookAnnotationState.Phase.QUEUED -> Text(stringResource(R.string.whole_book_queued),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                WholeBookAnnotationState.Phase.IDLE -> Text(
                    state.result?.asString() ?: stringResource(R.string.whole_book_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.active) {
                    MoReadButton(stringResource(R.string.action_stop), onClick = viewModel::stop, style = MoReadButtonStyle.Outlined)
                } else {
                    MoReadButton(stringResource(R.string.whole_book_start), onClick = { starting = true }, style = MoReadButtonStyle.Tonal)
                }
                MoReadButton(stringResource(R.string.whole_book_export), onClick = { exporter.launch(viewModel.suggestedPackName(bookTitle)) },
                    style = MoReadButtonStyle.Outlined, enabled = !state.packBusy)
                MoReadButton(stringResource(R.string.whole_book_import), onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    style = MoReadButtonStyle.Outlined, enabled = !state.packBusy && !state.active)
            }
        }
    }
    if (starting) WholeBookStartDialog(viewModel, onDismiss = { starting = false }, onStart = { from, voice, images ->
        starting = false
        viewModel.start(from, voice, images)
    })
}

@Composable
private fun WholeBookStartDialog(
    viewModel: BookAnnotationToolsViewModel,
    onDismiss: () -> Unit,
    onStart: (fromChapter: Int, voice: Boolean, images: Boolean) -> Unit
) {
    var fromCurrent by rememberSaveable { mutableStateOf(false) }
    var voice by rememberSaveable { mutableStateOf(false) }
    var images by rememberSaveable { mutableStateOf(false) }
    val estimate by produceState<WholeBookEstimate?>(null, fromCurrent) { value = viewModel.estimate(fromCurrent) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.whole_book_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceRow(stringResource(R.string.whole_book_from_first), selected = !fromCurrent) { fromCurrent = false }
                val current = estimate?.takeIf { fromCurrent || it.fromChapter > 0 }
                ChoiceRow(if (current != null) stringResource(R.string.whole_book_from_chapter, current.fromChapter + 1)
                    else stringResource(R.string.whole_book_from_current), selected = fromCurrent) { fromCurrent = true }
                val info = estimate
                Text(
                    if (info == null) stringResource(R.string.whole_book_estimating)
                    else stringResource(R.string.whole_book_estimate, info.chapters,
                        NumberFormat.getIntegerInstance().format(info.chars), info.personas, info.requests),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.whole_book_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (info?.voiceAvailable == true) CheckRow(stringResource(R.string.whole_book_voice), voice) { voice = it }
                if (info?.imagesAvailable == true) CheckRow(stringResource(R.string.whole_book_images), images) { images = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(if (fromCurrent) estimate?.fromChapter ?: 0 else 0, voice, images) },
                enabled = estimate != null) { Text(stringResource(R.string.action_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
