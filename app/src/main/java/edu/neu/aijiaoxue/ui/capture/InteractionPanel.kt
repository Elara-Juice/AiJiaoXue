package edu.neu.aijiaoxue.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.entity.InteractionEvent
import edu.neu.aijiaoxue.data.model.InteractionType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一行放 3 个按钮，课堂上单手也能点准。 */
private const val BUTTONS_PER_ROW = 3

/**
 * US12 采集页上的互动标注卡片：一键标注、各类型计数、5 秒撤销。
 * 业务规则 2：点击即写库，不等待对话框。“其他”写库后再弹框补充具体内容；
 * 其他类型的备注可在撤销条点“加备注”，或在下方“互动记录”里补。
 */
@Composable
fun InteractionPanel(
    enabled: Boolean,
    counts: Map<InteractionType, Int>,
    undoable: UndoableEvent?,
    onMark: (InteractionType) -> Unit,
    onUndo: () -> Unit,
    onAddNote: (Long) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("互动标注", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "共 ${counts.values.sum()} 次",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            InteractionType.entries.chunked(BUTTONS_PER_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { type ->
                        val count = counts[type] ?: 0
                        FilledTonalButton(
                            onClick = { onMark(type) },
                            enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .semantics { contentDescription = "标注${type.label}，已标注 $count 次" },
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(type.label, textAlign = TextAlign.Center, maxLines = 2)
                                Text("$count", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    // 最后一行不满时补位，保持按钮等宽
                    repeat(BUTTONS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            UndoBar(undoable, onUndo, onAddNote)
        }
    }
}

/** US12 异常处理：最近一次标注 5 秒内显示撤销入口，同时可以直接给这条加备注。 */
@Composable
private fun UndoBar(undoable: UndoableEvent?, onUndo: () -> Unit, onAddNote: (Long) -> Unit) {
    // 固定占位高度，入口出现、消失时按钮不跳动，避免误点
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (undoable == null) return@Row
        Text(
            "已标注“${undoable.type.label}” · 课堂第 ${formatDuration(undoable.offsetMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onAddNote(undoable.eventId) }) { Text("加备注") }
        TextButton(onClick = onUndo) { Text("撤销") }
    }
}

/** 一屏大约放 4 条，超出部分在卡片内滑动，不用把整页拉到底。 */
private val RECORD_LIST_MAX_HEIGHT = 360.dp

internal const val RECORD_LIST_TAG = "interaction-records"

/**
 * US12 主流程 4：按时间顺序查看本堂课全部互动事件。
 * 列表固定最大高度、卡片内单独滑动；新标注一条时自动滚到最新，方便马上加备注或删除。
 */
@Composable
fun InteractionRecordCard(
    events: List<InteractionEvent>,
    editable: Boolean,
    onEditNote: (Long) -> Unit,
    onDelete: (InteractionEvent) -> Unit,
) {
    val listState = rememberLazyListState()
    // 只在条数增加（新标注）时滚动；删除、改备注时保持当前位置
    var lastSize by remember { mutableIntStateOf(events.size) }
    LaunchedEffect(events.size) {
        if (events.size > lastSize && events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
        lastSize = events.size
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("互动记录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "共 ${events.size} 条，按时间排序",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (events.isEmpty()) {
                Text(
                    "暂无互动记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
                return@Column
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = RECORD_LIST_MAX_HEIGHT)
                    .testTag(RECORD_LIST_TAG),
            ) {
                itemsIndexed(events, key = { _, e -> e.id }) { index, event ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    InteractionEventRow(
                        event = event,
                        editable = editable,
                        onEditNote = { onEditNote(event.id) },
                        onDelete = { onDelete(event) },
                    )
                }
            }
        }
    }
}

/**
 * US12 主流程 4：互动记录中的一条。“备注”“删除”直接显示在行尾，
 * 超过 5 秒撤销时间后，误标也能在这里删除。
 */
@Composable
fun InteractionEventRow(
    event: InteractionEvent,
    editable: Boolean,
    onEditNote: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        // 和外层卡片同色，不显示成一块块白底
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(event.type.label) },
        supportingContent = {
            Column {
                Text(eventTimeLabel(event), color = MaterialTheme.colorScheme.onSurfaceVariant)
                event.note?.let { Text(it, maxLines = 3) }
            }
        },
        trailingContent = {
            if (editable) {
                Row {
                    TextButton(
                        onClick = onEditNote,
                        modifier = Modifier.semantics { contentDescription = "给${event.type.label}写备注" },
                    ) { Text(if (event.note == null) "备注" else "改备注") }
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.semantics { contentDescription = "删除${event.type.label}" },
                    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
    )
}

/**
 * US12 主流程 3：填写或修改备注。事件已经入库，这里只补充内容，取消也不会丢掉这次标注。
 * [isNewOther] 为刚点“其他”时，提示填写具体是什么互动。
 */
@Composable
fun InteractionNoteDialog(
    event: InteractionEvent,
    isNewOther: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by rememberSaveable(event.id) { mutableStateOf(event.note.orEmpty()) }
    val focusRequester = remember { FocusRequester() }
    // 弹出后直接聚焦输入框，少点一下
    LaunchedEffect(event.id) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNewOther) "已标注“其他”，补充具体内容" else "${event.type.label} · 备注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    eventTimeLabel(event),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(if (isNewOther) "具体内容，如：随堂测验、教师板演" else "备注（选填）") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (isNewOther) "稍后再填" else "取消") } },
    )
}

/** US12 主流程 4：删除误标事件前二次确认。 */
@Composable
fun DeleteEventDialog(event: InteractionEvent, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除互动记录") },
        text = { Text("确定删除 ${eventTimeLabel(event)} 的“${event.type.label}”吗？删除后不可恢复。") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** “10:15:30 · 课堂第 12:03”（US12 主流程 2）。 */
fun eventTimeLabel(event: InteractionEvent): String {
    val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(event.occurredAt))
    return "$time · 课堂第 ${formatDuration(event.offsetMs)}"
}
