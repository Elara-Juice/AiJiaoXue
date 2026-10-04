package edu.neu.aijiaoxue

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.DemoData
import edu.neu.aijiaoxue.data.Session
import edu.neu.aijiaoxue.navigation.AppNavHost
import edu.neu.aijiaoxue.ui.theme.AiJiaoXueTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // 首次启动写入演示数据（docs/演示数据.md），已有数据时不重复写入
            DemoData.seedIfEmpty(AppDatabase.get(applicationContext))
            // F0 业务规则 4：恢复上次的登录状态
            Session.restore(applicationContext)
        }
        enableEdgeToEdge()
        setContent {
            AiJiaoXueTheme {
                AppNavHost()
            }
        }
    }
}
