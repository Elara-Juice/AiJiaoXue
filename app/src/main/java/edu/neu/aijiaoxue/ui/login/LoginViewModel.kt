package edu.neu.aijiaoxue.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.neu.aijiaoxue.data.AppDatabase
import edu.neu.aijiaoxue.data.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val account: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

class LoginViewModel(application: Application) : AndroidViewModel(application) {
    private val userDao = AppDatabase.get(application).userDao()

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onAccountChange(value: String) = _uiState.update { it.copy(account = value, error = null) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }

    /** 登录成功后只写 Session，跳转由 AppNavHost 统一处理。 */
    fun login() {
        val state = _uiState.value
        if (state.isLoading) return
        if (state.account.isBlank() || state.password.isEmpty()) {
            _uiState.update { it.copy(error = "请输入账号和密码") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val user = Session.authenticate(userDao, state.account, state.password)
            if (user == null) {
                // F0 异常处理：不区分账号不存在还是密码错误
                _uiState.update { it.copy(isLoading = false, password = "", error = "账号或密码错误") }
            } else {
                Session.signIn(getApplication(), user)
                _uiState.value = LoginUiState()
            }
        }
    }
}
