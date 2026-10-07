package edu.neu.aijiaoxue.ui.evaluation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.Session
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@Composable
inline fun <reified T : ViewModel> moduleViewModel(crossinline create: (AppDatabase, Long) -> T): T {
    val database = AppDatabase.get(LocalContext.current)
    val actorId = Session.requireUser().id
    return viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create(database, actorId) as VM
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulePage(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } })
    }) { padding -> content(padding) }
}

@Composable
fun MessageCard(message: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(12.dp))
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun ReadField(label: String, value: String?) {
    Text("$label：${value?.takeIf { it.isNotBlank() } ?: "未填写"}")
}

fun timeText(value: Long?): String = value?.let {
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))
} ?: "未记录"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true, clearable: Boolean = true) {
    var selecting by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { selecting = true }, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text("$label：${value.ifEmpty { "请选择" }}")
        }
        if (clearable && value.isNotEmpty()) TextButton(onClick = { onChange("") }, enabled = enabled) { Text("清除") }
    }
    if (selecting) {
        val picker = remember { DatePickerState(locale = Locale.SIMPLIFIED_CHINESE,
            initialSelectedDateMillis = value.takeIf(::validDate)?.let {
                LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }) }
        DatePickerDialog(onDismissRequest = { selecting = false }, confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                selecting = false
            }) { Text("确定日期") }
        }, dismissButton = { TextButton(onClick = { selecting = false }) { Text("取消") } }) {
            DatePicker(state = picker, title = { Text("选择日期", Modifier.padding(start = 24.dp, top = 16.dp)) },
                headline = { Text(picker.selectedDateMillis?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                } ?: "请选择日期", Modifier.padding(start = 24.dp, bottom = 16.dp)) })
        }
    }
}

@Composable
fun ErrorPage(title: String, message: String, onBack: () -> Unit, retry: () -> Unit) {
    ModulePage(title, onBack) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MessageCard(message, error = true)
            OutlinedButton(onClick = retry) { Text("重试") }
        }
    }
}
