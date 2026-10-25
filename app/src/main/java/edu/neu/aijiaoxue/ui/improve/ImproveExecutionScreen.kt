package edu.neu.aijiaoxue.ui.improve

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import edu.neu.aijiaoxue.data.model.DateFormats
import edu.neu.aijiaoxue.data.model.MeasureStatus
import edu.neu.aijiaoxue.data.model.PlanStatus
import edu.neu.aijiaoxue.ui.evaluation.*
import java.time.Instant
import java.time.ZoneId

@Composable
fun ImproveExecutionScreen(viewModel: ImproveExecutionViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var attachmentExecutionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var evidenceDescription by rememberSaveable { mutableStateOf("") }
    var confirmLeaving by rememberSaveable { mutableStateOf(false) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), viewModel::onEvidenceSelected)
    val interactive = !state.busy && !state.loading
    val record = state.context?.record
    val selected = record?.measures?.find { it.id == state.selectedMeasureId }
    val draft = state.selectedMeasureId?.let { state.drafts[it] }
    val unsaved = state.drafts.values.any {
        it.implementationNote.isNotBlank() || it.actualAdjustment.isNotBlank() || it.status != null
    }
    val leave: () -> Unit = {
        if (state.busy) viewModel.reportError("正在保存或读取，请稍候再返回")
        else if (unsaved) confirmLeaving = true
        else onBack()
    }
    BackHandler(onBack = leave)
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!state.loading && record == null) {
        ErrorPage("改进落实与证据", state.error ?: "加载失败", leave, viewModel::reload)
        return
    }

    ModulePage("改进落实与证据", leave) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().testTag("execution-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(record?.course?.name ?: "加载改进计划") {
                    if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    record?.let {
                        Text("督导日期：${it.task.supervisionDate}")
                        Text("原计划${if (it.plan?.status == PlanStatus.CONFIRMED) "已确认，内容只读" else "尚未确认"}")
                        Text("记录实际采取的调整，并为每次实施补充证据。原改进重点、措施和计划完成日期保持原记录。")
                    }
                    state.message?.let { MessageCard(it) }
                    state.error?.let { MessageCard(it, true) }
                    TextButton(onClick = viewModel::reload, enabled = interactive, modifier = Modifier.testTag("execution-refresh")) {
                        Text("刷新记录")
                    }
                    state.pendingEvidence?.takeIf { it.uri != null }?.let {
                        Text("有一份实施证据尚未导入，可沿用原文件重试。")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = viewModel::retryEvidenceImport, enabled = interactive) { Text("重试导入") }
                            TextButton(onClick = viewModel::clearPendingEvidence, enabled = interactive) { Text("取消本次导入") }
                        }
                    }
                }
            }
            if (record != null && record.measures.isEmpty()) {
                item { MessageCard("此计划暂无改进措施，请先完成并确认改进计划。") }
            }
            record?.measures?.forEachIndexed { index, measure ->
                item(key = "select-${measure.id}") {
                    FilterChip(
                        selected = selected?.id == measure.id,
                        onClick = { viewModel.selectMeasure(measure.id) }, enabled = interactive,
                        label = { Text("措施 ${index + 1} · ${measure.content}") },
                        modifier = Modifier.fillMaxWidth().testTag("execution-select-${measure.id}"),
                    )
                }
            }
            if (record != null && selected != null) {
                item(key = "original-${selected.id}") {
                    val originalItem = record.items.find { it.id == selected.itemId }
                    val suggestion = record.suggestions.find { it.id == selected.suggestionId }
                    SectionCard("原问题与确认计划") {
                        ReadField("原改进问题", originalItem?.description)
                        ReadField("原计划措施", selected.content)
                        Text("计划完成日期：${selected.dueDate} · 当前状态：${selected.status.label}")
                        Text("措施来源：${selected.source.label}")
                        if (suggestion != null) {
                            ReadField("已采用建议摘要", suggestion.content)
                            ReadField("建议理由", suggestion.rationale)
                            ReadField("对应教学环节", suggestion.bopppsStage)
                            ReadField("审核来源版本", suggestion.reviewVersionKey)
                        } else {
                            ReadField("督导参考建议", record.evaluation.suggestions)
                        }
                    }
                }
                if (draft != null && record.plan?.status == PlanStatus.CONFIRMED) {
                    item(key = "form-${selected.id}") {
                        SectionCard("新增一次落实记录") {
                            OutlinedTextField(
                                value = draft.implementationNote, onValueChange = viewModel::setImplementationNote,
                                label = { Text("实施说明") }, supportingText = { Text("说明本次如何落实这条措施。") },
                                enabled = interactive, minLines = 3,
                                modifier = Modifier.fillMaxWidth().testTag("execution-note"),
                            )
                            OutlinedTextField(
                                value = draft.actualAdjustment, onValueChange = viewModel::setActualAdjustment,
                                label = { Text("实际调整内容") }, supportingText = { Text("记录实际采用的做法，供后续课堂复评调用。") },
                                enabled = interactive, minLines = 3,
                                modifier = Modifier.fillMaxWidth().testTag("execution-adjustment"),
                            )
                            OutlinedTextField(
                                value = draft.implementationDate, onValueChange = viewModel::setImplementationDate,
                                label = { Text("实施日期（yyyy-MM-dd）") }, singleLine = true, enabled = interactive,
                                modifier = Modifier.fillMaxWidth().testTag("execution-date"),
                            )
                            Text("同时更新措施状态（可选）")
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = draft.status == null, onClick = { viewModel.setStatus(null) },
                                    enabled = interactive, label = { Text("保持当前状态") })
                                MeasureStatus.entries.forEach { status ->
                                    FilterChip(selected = draft.status == status, onClick = { viewModel.setStatus(status) },
                                        enabled = interactive, label = { Text(status.label) },
                                        modifier = Modifier.testTag("execution-status-${status.name}"))
                                }
                            }
                            Button(onClick = viewModel::save, enabled = interactive,
                                modifier = Modifier.fillMaxWidth().testTag("execution-save")) { Text("保存落实记录") }
                            Text("保存后可为该记录添加图片或教学资料。")
                        }
                    }
                }
                val entries = state.context?.executions.orEmpty().filter { it.execution.measureId == selected.id }
                item { Text("这条措施的落实历史 · ${entries.size} 次", style = MaterialTheme.typography.titleMedium) }
                if (entries.isEmpty()) item { MessageCard("暂无落实记录，填写上方表单后保存。") }
                entries.forEach { entry ->
                    item(key = "execution-${entry.execution.id}") {
                        val execution = entry.execution
                        SectionCard("实施日期：${Instant.ofEpochMilli(execution.implementedAt).atZone(ZoneId.systemDefault()).toLocalDate().format(DateFormats.DATE)}") {
                            Column(Modifier.testTag("execution-history-${execution.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ReadField("实施说明", execution.implementationNote)
                                ReadField("实际调整", execution.actualAdjustment)
                                Text("记录时间：${timeText(execution.createdAt)}")
                                Text("实施证据 · ${entry.evidence.size} 份")
                                if (entry.evidence.isEmpty()) Text("尚未添加证据")
                                entry.evidence.forEach { evidence ->
                                    TextButton(onClick = {
                                        viewModel.openEvidence(evidence.id) { opened ->
                                            try {
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", opened.file)
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, opened.mimeType)
                                                    clipData = ClipData.newUri(context.contentResolver, opened.displayName, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(intent)
                                            } catch (_: ActivityNotFoundException) {
                                                viewModel.reportError("没有可打开此文件的应用，请安装图片或 PDF 查看器后重试")
                                            } catch (_: SecurityException) {
                                                viewModel.reportError("无法授权打开此证据，请返回后重试")
                                            }
                                        }
                                    }, enabled = interactive, modifier = Modifier.testTag("execution-evidence-${evidence.id}")) {
                                        Text("打开 ${evidence.displayName}（${evidence.byteSize / 1024} KB）")
                                    }
                                    evidence.description.takeIf { it.isNotBlank() }?.let { Text(it) }
                                }
                                OutlinedButton(onClick = {
                                    attachmentExecutionId = execution.id
                                    evidenceDescription = ""
                                }, enabled = interactive, modifier = Modifier.testTag("execution-add-evidence-${execution.id}")) {
                                    Text("添加实施证据")
                                }
                                Text("支持 JPEG、PNG 图片及 PDF 教学资料，单个文件不超过 10 MiB。")
                            }
                        }
                    }
                }
            }
        }
    }

    if (attachmentExecutionId != null) {
        AlertDialog(
            onDismissRequest = { attachmentExecutionId = null },
            title = { Text("添加实施证据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("选择 JPEG、PNG 或 PDF，单个文件不超过 10 MiB。")
                    OutlinedTextField(value = evidenceDescription, onValueChange = { evidenceDescription = it },
                        label = { Text("证据说明（可选）") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = attachmentExecutionId ?: return@TextButton
                    if (viewModel.prepareEvidence(id, evidenceDescription)) {
                        attachmentExecutionId = null
                        try { documentPicker.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) }
                        catch (_: ActivityNotFoundException) {
                            viewModel.clearPendingEvidence()
                            viewModel.reportError("当前设备无法打开文件选择器")
                        } catch (_: SecurityException) {
                            viewModel.clearPendingEvidence()
                            viewModel.reportError("当前设备不允许访问文件选择器")
                        }
                    }
                }, enabled = interactive) { Text("选择文件") }
            },
            dismissButton = { TextButton(onClick = { attachmentExecutionId = null }) { Text("取消") } },
        )
    }
    if (confirmLeaving) {
        AlertDialog(onDismissRequest = { confirmLeaving = false }, title = { Text("还有未保存的落实内容") },
            text = { Text("返回会放弃尚未保存的表单，已保存的历史记录不受影响。") },
            confirmButton = { TextButton(onClick = { confirmLeaving = false; onBack() }) { Text("放弃并返回") } },
            dismissButton = { TextButton(onClick = { confirmLeaving = false }) { Text("继续填写") } })
    }
}
