package edu.neu.aijiaoxue.ui.supervisor

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.model.*
import edu.neu.aijiaoxue.navigation.Routes
import edu.neu.aijiaoxue.ui.components.StatusBadge
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import edu.neu.aijiaoxue.ui.evaluation.timeText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorIssueScreen(vm: SupervisorIssueViewModel, onBack: () -> Unit, navigate: (String) -> Unit) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val detail = state.detail
    Scaffold(topBar = { TopAppBar(title = { Text("问题与改进") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) },
        bottomBar = { detail?.let { data -> Surface(shadowElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                val latest = data.tracked.latestReview
                val followUp = latest?.followUpTask
                val needsVerification = latest != null && (latest.verifications.isEmpty() || latest.verifications.first().id in latest.verificationWarnings)
                val label = when {
                    data.tracked.stage == TrackingStage.CLOSED -> "查看关闭依据"
                    followUp != null && followUp.status != TaskStatus.COMPLETED -> "继续复评课堂"
                    needsVerification -> "核验改善程度"
                    latest != null -> "安排下一次复评"
                    else -> "创建复评任务"
                }
                Button(onClick = { navigate(when {
                    data.tracked.stage == TrackingStage.CLOSED -> Routes.reviewDetail(requireNotNull(latest).link.id)
                    followUp != null && followUp.status != TaskStatus.COMPLETED -> Routes.taskDetail(followUp.id)
                    needsVerification -> Routes.reviewDetail(requireNotNull(latest).link.id)
                    else -> Routes.followUpCreate(data.tracked.row.id)
                }) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("issue-next")) { Text(label) }
            }
        } } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("issue-detail"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = vm::reload) { Text("重新加载") } } }
            detail?.let { data ->
                val row = data.tracked.row
                item { StatusBadge(data.tracked.stage.label); Text(row.description, style = MaterialTheme.typography.headlineSmall); Text("${row.courseName} · ${row.teacherName} · 问题 #${row.id}") }
                item { PortalCard {
                    Text("原问题与依据", style = MaterialTheme.typography.titleMedium)
                    Text("${row.supervisionDate} · ${row.dimension?.label ?: "综合意见"}")
                    Text(if (row.teacherReadAt == null) "教师尚未查看报告" else "教师已查看 · ${timeText(row.teacherReadAt)}")
                    TextButton(onClick = { navigate(Routes.historyDetail(row.taskId)) }) { Text("查看原报告与课堂证据") }
                    data.suggestions?.takeIf { it.isNotBlank() }?.let { Text("督导改进建议", style = MaterialTheme.typography.titleSmall); Text(it) }
                } }
                item { PortalCard {
                    Text("教师改进计划", style = MaterialTheme.typography.titleMedium)
                    if (data.measures.isEmpty()) Text(if (row.planConfirmed) "教师已确认计划，但尚未为本问题列出措施。可结合原报告进一步沟通。" else "等待教师选择改进重点并确认计划。")
                    data.measures.forEach { measure ->
                        Text(measure.content, style = MaterialTheme.typography.titleSmall)
                        Text("${measure.status.label} · 期限 ${measure.dueDate}")
                        if (isMeasureOverdue(measure.status, measure.dueDate)) Text("措施已逾期", color = MaterialTheme.colorScheme.error)
                        HorizontalDivider()
                    }
                    Text("措施完成表示教师已采取行动；是否改善仍需复评核验。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { Text("实施记录 · ${data.executions.size} 条", style = MaterialTheme.typography.titleMedium) }
                if (data.executions.isEmpty()) item { Text("尚未提交实施记录。教师可在改进待办中记录实际做法并添加证据。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                data.executions.forEach { entry -> item(key = "execution-${entry.execution.id}") { PortalCard {
                    Text(timeText(entry.execution.implementedAt), style = MaterialTheme.typography.labelLarge)
                    Text(entry.execution.implementationNote)
                    Text("实际调整：${entry.execution.actualAdjustment}")
                    entry.evidence.forEach { evidence -> TextButton(onClick = {
                        vm.openEvidence(context, evidence.id) { file ->
                            try {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file.file)
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri,file.mimeType); clipData = ClipData.newUri(context.contentResolver,file.displayName,uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
                            } catch (_: Exception) { vm.reportError("当前设备无法打开此附件，请检查图片或 PDF 查看器") }
                        }
                    }) { Text("查看材料：${evidence.displayName}") }; if (evidence.description.isNotBlank()) Text(evidence.description,style = MaterialTheme.typography.bodySmall) }
                } } }
                item { Text("复评与核验", style = MaterialTheme.typography.titleMedium) }
                if (data.reviews.isEmpty()) item { Text("创建复评任务后，会自动关联本问题并带入课堂档案。") }
                data.reviews.forEach { review -> item(key = "review-${review.link.id}") { PortalCard {
                    Text("${review.followUpTask?.supervisionDate} · 第 ${review.followUpTask?.period} 节", style = MaterialTheme.typography.titleSmall)
                    Text(review.verifications.firstOrNull()?.let { if (it.id in review.verificationWarnings) "原核验依据需重新核对" else it.verdict.label } ?: "等待复评与人工核验")
                    review.verifications.firstOrNull()?.let { Text(it.basis) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { navigate(Routes.taskDetail(review.link.followUpTaskId)) }) { Text("复评课堂") }
                        TextButton(onClick = { navigate(Routes.reviewDetail(review.link.id)) }) { Text("证据对照与核验") }
                    }
                } } }
            }
        }
    }
}

@Composable
fun FollowUpCreateScreen(vm: SupervisorIssueViewModel, onBack: () -> Unit, onCreated: (Long) -> Unit) {
    val state by vm.uiState.collectAsState()
    var date by rememberSaveable { mutableStateOf(DateFormats.today()) }
    var start by rememberSaveable { mutableIntStateOf(1) }
    var end by rememberSaveable { mutableIntStateOf(2) }
    var chapter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.createdTaskId) { state.createdTaskId?.let(onCreated) }
    ModulePage("创建问题复评", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.detail?.let { detail ->
                item { Text(detail.tracked.row.courseName, style = MaterialTheme.typography.headlineSmall); Text("任课教师：${detail.tracked.row.teacherName}") }
                item { PortalCard { Text("本次复评关注",style = MaterialTheme.typography.titleMedium); Text(detail.tracked.row.description); Text("原课堂：${detail.tracked.row.supervisionDate}", style = MaterialTheme.typography.bodySmall) } }
                item { OutlinedTextField(date, { date = it }, enabled = !state.busy, label = { Text("听课日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("followup-date")) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PeriodPicker("开始",start,!state.busy,Modifier.weight(1f)) { start=it; if(end<it) end=it }
                    PeriodPicker("结束",end,!state.busy,Modifier.weight(1f)) { end=it }
                } }
                item { OutlinedTextField(chapter,{chapter=it},enabled=!state.busy,label={Text("本次授课章节")},modifier=Modifier.fillMaxWidth()) }
                item { Text("课程、教师、原问题会自动关联。复评日期和节次须晚于原课堂。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Button(onClick={vm.create(date.trim(),start,end,chapter)},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("followup-create")) { Text(if(state.busy) "正在创建…" else "创建并查看任务") } }
            }
            state.error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
private fun PeriodPicker(label:String,value:Int,enabled:Boolean,modifier:Modifier,onSelect:(Int)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) { OutlinedButton(onClick={open=true},enabled=enabled,modifier=Modifier.fillMaxWidth()) { Text("$label · 第 $value 节") }
        DropdownMenu(open,{open=false}) { (1..12).forEach { n->DropdownMenuItem(text={Text("第 $n 节")},onClick={onSelect(n);open=false}) } } }
}
