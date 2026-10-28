package edu.neu.aijiaoxue.ui.evaluation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.ui.theme.AppGlyph
import edu.neu.aijiaoxue.ui.theme.AppIcon
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
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        AppIcon(AppGlyph.BACK, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { padding -> content(padding) }
}

@Composable
fun MessageCard(message: String, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AppIcon(
                glyph = if (error) AppGlyph.ERROR else AppGlyph.INFO,
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.width(4.dp).height(19.dp).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary))
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
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
