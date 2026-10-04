package edu.neu.aijiaoxue.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.Session

/** F0 主流程 3：“我的”页面，查看当前账号并退出登录。退出后 AppNavHost 自动回到登录页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val user by Session.user.collectAsState()
    var confirming by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            user?.let {
                ListItem(headlineContent = { Text("姓名") }, trailingContent = { Text(it.name) })
                HorizontalDivider()
                ListItem(headlineContent = { Text("账号") }, trailingContent = { Text(it.account) })
                HorizontalDivider()
                ListItem(headlineContent = { Text("角色") }, trailingContent = { Text(it.role.label) })
                HorizontalDivider()
            }
            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                onClick = { confirming = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp),
            ) { Text("退出登录") }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("退出登录") },
            text = { Text("退出后需要重新登录才能使用。") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    Session.signOut(context)
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } },
        )
    }
}
