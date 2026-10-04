package edu.neu.aijiaoxue.data

import android.content.Context
import androidx.core.content.edit
import edu.neu.aijiaoxue.data.dao.UserDao
import edu.neu.aijiaoxue.data.entity.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前登录用户的唯一来源。各页面按 [currentUser] 的 id 查询本人数据（F0 业务规则 2），不要自己保存登录状态。
 * F0 业务规则 4：登录状态在应用重启后保持，只存用户 id，不存密码。
 */
object Session {
    private const val PREFS = "session"
    private const val KEY_USER_ID = "userId"

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    /** [restore] 完成前为 false，界面显示加载中，避免先闪一下登录页。 */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val currentUser: User? get() = _user.value

    /** 进入业务页面时一定已登录（AppNavHost 已拦截），直接取用。 */
    fun requireUser(): User = checkNotNull(_user.value) { "未登录" }

    /** 启动时调用。数据库被清空（版本升级、清除数据）后找不到该用户，视为未登录。 */
    suspend fun restore(context: Context) {
        val prefs = prefs(context)
        val id = prefs.getLong(KEY_USER_ID, 0L)
        val user = if (id > 0) AppDatabase.get(context).userDao().getById(id) else null
        if (user == null) prefs.edit { remove(KEY_USER_ID) }
        _user.value = user
        _ready.value = true
    }

    fun signIn(context: Context, user: User) {
        prefs(context).edit { putLong(KEY_USER_ID, user.id) }
        _user.value = user
        _ready.value = true
    }

    /** F0 验收③：退出后清除登录状态，AppNavHost 随即回到登录页并清空返回栈。 */
    fun signOut(context: Context) {
        prefs(context).edit { remove(KEY_USER_ID) }
        _user.value = null
    }

    /** 账号去掉首尾空格，密码原样校验。账号不存在和密码错误一律返回 null，不区分（F0 异常处理）。 */
    suspend fun authenticate(userDao: UserDao, account: String, password: String): User? {
        val user = userDao.getByAccount(account.trim()) ?: return null
        return user.takeIf { PasswordHasher.verify(password, it.passwordHash) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
