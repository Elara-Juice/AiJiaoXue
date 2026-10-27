package edu.neu.aijiaoxue.ui.input

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.repository.EvidenceSourceResolver
import edu.neu.aijiaoxue.data.repository.ResolvedEvidenceSource
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import kotlinx.coroutines.CancellationException

@Composable
fun PublishedEvidenceScreen(actorId: Long, taskId: Long, evidenceId: Long, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var source by remember { mutableStateOf<ResolvedEvidenceSource?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(actorId, taskId, evidenceId) {
        try { source = EvidenceSourceResolver(context, AppDatabase.get(context)).openPublished(actorId, taskId, evidenceId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "来源无法打开" }
    }
    ModulePage("正式报告来源", onBack) { padding ->
        if (error != null) Text(error!!, Modifier.padding(padding).padding(20.dp))
        else if (source == null) CircularProgressIndicator(Modifier.padding(padding).padding(20.dp))
    }
    source?.let { EvidenceSourceDialog(it, onBack) }
}
