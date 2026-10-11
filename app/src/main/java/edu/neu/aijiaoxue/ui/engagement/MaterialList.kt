package edu.neu.aijiaoxue.ui.engagement

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** US11 主流程 1：材料行尾的“删除”按钮。读屏读出删除的是哪一份材料。 */
@Composable
fun DeleteMaterialButton(title: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "删除$title" },
    ) { Text("删除", color = MaterialTheme.colorScheme.error) }
}

/**
 * US11 业务规则 1：删除前二次确认；异常处理：取消则不做任何修改。
 * 业务规则 3：Sprint 1 为物理删除，文件和记录都不保留，所以提示“不可恢复”。
 */
@Composable
fun DeleteMaterialDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除材料") },
        text = { Text("确定删除“$title”吗？文件和记录都会删除，不可恢复。删除后可以重新采集。") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
