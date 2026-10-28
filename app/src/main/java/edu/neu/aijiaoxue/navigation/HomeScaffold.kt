package edu.neu.aijiaoxue.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.data.model.DateFormats
import edu.neu.aijiaoxue.ui.components.SectionHeading
import edu.neu.aijiaoxue.ui.theme.AppGlyph
import edu.neu.aijiaoxue.ui.theme.AppIcon
import edu.neu.aijiaoxue.ui.theme.Blue
import edu.neu.aijiaoxue.ui.theme.Mist
import edu.neu.aijiaoxue.ui.theme.Navy
import edu.neu.aijiaoxue.ui.theme.NavyDeep
import edu.neu.aijiaoxue.ui.theme.PaleBlue
import edu.neu.aijiaoxue.ui.theme.Sky
import edu.neu.aijiaoxue.ui.theme.White

/** 首页上的一个功能入口。badge > 0 时显示数字角标（如未读反馈）。 */
data class HomeEntry(
    val title: String,
    val description: String,
    val route: String,
    val badge: Int = 0,
)

/** 三类角色共用的深蓝工作台，保留各角色原有入口和首页特色内容。 */
@Composable
fun HomeScaffold(
    entries: List<HomeEntry>,
    onNavigate: (String) -> Unit,
    featuredContent: (@Composable () -> Unit)? = null,
) {
    val user by Session.user.collectAsState()
    val rows = entries.chunked(2)

    Scaffold(
        containerColor = Mist,
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Brush.horizontalGradient(listOf(NavyDeep, Navy, Blue))),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(15.dp), color = White.copy(alpha = 0.14f)) {
                        AppIcon(
                            AppGlyph.SCHOOL,
                            tint = White,
                            modifier = Modifier.padding(11.dp).size(25.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("爱教学", style = MaterialTheme.typography.titleMedium, color = White.copy(alpha = 0.82f))
                        Text(
                            text = user?.let { "你好，${it.name}" } ?: "教学工作台",
                            style = MaterialTheme.typography.titleLarge,
                            color = White,
                        )
                    }
                    IconButton(onClick = { onNavigate(Routes.PROFILE) }) {
                        AppIcon(AppGlyph.PERSON, contentDescription = "个人中心", tint = White)
                    }
                }
                Text(
                    text = user?.role?.label ?: "教学质量协同平台",
                    style = MaterialTheme.typography.bodyMedium,
                    color = White.copy(alpha = 0.82f),
                    modifier = Modifier.padding(start = 24.dp, bottom = 18.dp),
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(Modifier.padding(start = 4.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(DateFormats.today(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("工作台", style = MaterialTheme.typography.titleLarge, color = NavyDeep)
                    Text("让每一堂课的进步有据可循", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            featuredContent?.let { content -> item { content() } }
            item { SectionHeading("工作入口") }
            itemsIndexed(rows, key = { _, row -> row.joinToString("-") { it.route } }) { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEachIndexed { columnIndex, entry ->
                        HomeFeatureCard(
                            entry = entry,
                            icon = entryIcon(entry.route),
                            iconColor = if ((rowIndex + columnIndex) % 2 == 0) Navy else Blue,
                            iconBackground = if ((rowIndex + columnIndex) % 2 == 0) PaleBlue else Color(0xFFE8F5F3),
                            onClick = { onNavigate(entry.route) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HomeFeatureCard(
    entry: HomeEntry,
    icon: AppGlyph,
    iconColor: Color,
    iconBackground: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(160.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Surface(shape = RoundedCornerShape(14.dp), color = iconBackground) {
                    AppIcon(icon, tint = iconColor, modifier = Modifier.padding(10.dp).size(24.dp))
                }
                Spacer(Modifier.weight(1f))
                if (entry.badge > 0) {
                    Badge(Modifier.semantics { contentDescription = "${entry.badge} 条未读" }) {
                        Text(entry.badge.toString())
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        color = NavyDeep,
                    )
                    AppIcon(AppGlyph.CHEVRON_RIGHT, tint = Sky, modifier = Modifier.size(20.dp))
                }
                Text(
                    entry.description,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun entryIcon(route: String): AppGlyph = when (route) {
    Routes.COURSE_SEARCH -> AppGlyph.SEARCH
    Routes.MY_TASKS -> AppGlyph.TASK
    Routes.HISTORY -> AppGlyph.HISTORY
    Routes.REVIEW_LIST -> AppGlyph.COMPARE
    Routes.ARRANGEMENT -> AppGlyph.CALENDAR
    Routes.TASK_PROGRESS -> AppGlyph.REPORT
    Routes.FEEDBACK_LIST -> AppGlyph.FEEDBACK
    Routes.IMPROVE_TODO -> AppGlyph.CHECKLIST
    Routes.TEACHING_SUPPORT -> AppGlyph.IDEA
    Routes.GOVERNANCE -> AppGlyph.TREND
    Routes.ABNORMAL_COURSES -> AppGlyph.ERROR
    Routes.SUPERVISOR_TRACKING, Routes.TASK_TRACKING -> AppGlyph.TREND
    else -> AppGlyph.BOOK
}
