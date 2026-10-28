package edu.neu.aijiaoxue.ui.supervisor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.remote.*
import edu.neu.aijiaoxue.data.model.ConnectionCheckState
import edu.neu.aijiaoxue.navigation.Routes
import edu.neu.aijiaoxue.ui.evaluation.ModulePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorProfileScreen(navigate: (String) -> Unit) {
    val user by Session.user.collectAsState()
    val context = LocalContext.current
    var help by rememberSaveable { mutableStateOf(false) }
    var logout by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("我的") }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { PortalCard { Text(user?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall); Text(user?.role?.label.orEmpty()); Text("账号：${user?.account.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item { PortalCard {
                Text("分析与使用", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { navigate(Routes.ANALYSIS_SETTINGS) },modifier=Modifier.fillMaxWidth()) { Text("分析服务设置") }
                OutlinedButton(onClick = { help = true },modifier=Modifier.fillMaxWidth()) { Text("督导流程使用帮助") }
            } }
            item { PortalCard { Text("关于爱教学",style=MaterialTheme.typography.titleMedium); Text("教学质量全链路数字化管理平台"); Text("采集 → 分析核验 → 评价 → 改进 → 复评",style=MaterialTheme.typography.bodyMedium) } }
            item { OutlinedButton(onClick={logout=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("退出登录") } }
        }
    }
    if(help) AlertDialog(onDismissRequest={help=false},title={Text("如何完成一次督导")},text={Text("1. 在督导任务中查找课程并创建任务。\n2. 在课堂档案查看目标，开始采集录音、图片与课堂记录。\n3. 结束采集后检查材料并开始分析。\n4. 查看原始证据，核验候选结论。\n5. 填写人工评价并发布报告。\n6. 在改进跟踪中查看教师确认的措施和实施材料。\n7. 从具体问题创建复评任务，发布复评报告后核验改善程度。\n\nAI 不可用时仍可完成人工评价。教师完成措施不等于问题已经改善。")},confirmButton={TextButton(onClick={help=false}){Text("知道了")}})
    if(logout) AlertDialog(onDismissRequest={logout=false},title={Text("退出登录")},text={Text("退出后需要重新登录。已保存的任务和材料会保留。")},confirmButton={TextButton(onClick={logout=false;Session.signOut(context)}){Text("退出")}},dismissButton={TextButton(onClick={logout=false}){Text("取消")}})
}

@Composable
fun AnalysisServiceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AnalysisSettings(context) }
    val initial = remember { settings.get() }
    var url by rememberSaveable { mutableStateOf(initial.baseUrl) }
    // Tokens are not written to savedInstanceState or logs.
    var token by remember { mutableStateOf(initial.token) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<AnalysisConnectionReport?>(null) }
    val scope=rememberCoroutineScope()
    ModulePage("分析服务设置",onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Text("连接团队分析服务",style=MaterialTheme.typography.headlineSmall);Text("填写分析代理地址和设备令牌。DeepSeek、百炼密钥由服务端配置。") }
            item { OutlinedTextField(url,{url=it;report=null},label={Text("代理地址")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth()) }
            item { OutlinedTextField(token,{token=it;report=null},label={Text("设备令牌")},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth()) }
            item { Button(onClick={
                error=null;message=null
                try { settings.save(url,token);message="设置已保存" } catch(e:Exception) { error=e.message }
            },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("保存设置") } }
            item { OutlinedButton(onClick={
                busy=true;error=null;message=null
                scope.launch { try { settings.save(url,token);report=AnalysisConnectionChecker(context).check();message="连接检查完成" }
                    catch(e:CancellationException){throw e} catch(e:Exception){error=e.message ?: "无法连接分析服务"} finally {busy=false} }
            },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text(if(busy) "检查中…" else "保存并检查连接") } }
            message?.let { item { Text(it,color=MaterialTheme.colorScheme.primary) } }
            error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
            report?.checks?.forEach { check->item { PortalCard { Text(check.label,style=MaterialTheme.typography.titleSmall);Text(when(check.state){ConnectionCheckState.PASSED->"通过";ConnectionCheckState.FAILED->"待处理";else->"尚未验证"});Text(check.detail,style=MaterialTheme.typography.bodySmall) } } }
            item { Text("连接检查不上传课堂材料、不调用模型推理。实际转写与分析需在课堂任务内发起。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
