package edu.neu.aijiaoxue.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.Session

/** 首页上的一个功能入口。badge > 0 时显示数字角标（如 US33 未读反馈）。 */
data class HomeEntry(
    val title: String,
    val description: String,
    val route: String,
    val badge: Int = 0,
)

/** 三个角色首页共用的布局：顶栏显示姓名和角色，右上角进入“我的”。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScaffold(
    entries: List<HomeEntry>,
    onNavigate: (String) -> Unit,
) {
    val user by Session.user.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(user?.let { "${it.name}（${it.role.label}）" } ?: "爱教学")
                },
                actions = { TextButton(onClick = { onNavigate(Routes.PROFILE) }) { Text("我的") } },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries, key = { it.route }) { entry ->
                Card(
                    onClick = { onNavigate(entry.route) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                entry.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (entry.badge > 0) {
                            Badge(Modifier.semantics { contentDescription = "${entry.badge} 条未读" }) {
                                Text(entry.badge.toString())
                            }
                        }
                    }
                }
            }
        }
    }
}
