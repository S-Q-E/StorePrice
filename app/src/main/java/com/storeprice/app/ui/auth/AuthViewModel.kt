package com.storeprice.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.storeprice.app.core.PhoneNumber
import com.storeprice.app.data.auth.AuthRepository
import com.storeprice.app.domain.auth.AuthState
import com.storeprice.app.domain.auth.SignInResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Ошибки формы входа, которые можно показать пользователю. */
enum class LoginError {
    INVALID_PHONE,
    PASSWORD_REQUIRED,
    INVALID_CREDENTIALS,
    ACCOUNT_INACTIVE,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_NOT_CONFIRMED,
    SESSION_EXPIRED,
    PHONE_LOGIN_DISABLED,
    CONFIGURATION_MISSING,
    TOO_MANY_ATTEMPTS,
    NETWORK,
    SERVER,
}

data class AuthUiState(
    val authState: AuthState = AuthState.Initializing,
    val phone: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val loginError: LoginError? = null,
)

class AuthViewModel(
    private val repository: AuthRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var lastSilentRefreshAtMillis = clock()

    init {
        viewModelScope.launch {
            repository.state.collect { authState ->
                _uiState.update { current ->
                    current.copy(
                        authState = authState,
                        isSubmitting = if (authState is AuthState.SignedIn) {
                            false
                        } else {
                            current.isSubmitting
                        },
                        password = if (authState is AuthState.SignedIn) {
                            ""
                        } else {
                            current.password
                        },
                    )
                }
            }
        }
    }

    fun onPhoneChange(value: String) {
        _uiState.update { it.copy(phone = value, loginError = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, loginError = null) }
    }

    fun signIn() {
        val current = _uiState.value
        if (current.isSubmitting) return

        val normalizedPhone = PhoneNumber.normalize(current.phone)
        when {
            normalizedPhone == null -> {
                _uiState.update { it.copy(loginError = LoginError.INVALID_PHONE) }
                return
            }

            current.password.isBlank() -> {
                _uiState.update { it.copy(loginError = LoginError.PASSWORD_REQUIRED) }
                return
            }
        }

        _uiState.update { it.copy(isSubmitting = true, loginError = null) }
        viewModelScope.launch {
            val result = repository.signIn(normalizedPhone, current.password)
            _uiState.update { state ->
                when (result) {
                    SignInResult.Success -> state.copy(
                        isSubmitting = false,
                        password = "",
                    )

                    else -> state.copy(
                        isSubmitting = false,
                        loginError = result.toLoginError(),
                    )
                }
            }
        }
    }

    fun signOut() {
        _uiState.update { it.copy(isSubmitting = false, password = "", loginError = null) }
        viewModelScope.launch {
            repository.signOut()
        }
    }

    fun retrySession() {
        _uiState.update { it.copy(loginError = null) }
        viewModelScope.launch {
            repository.retrySession()
        }
    }

    /**
     * Тихо перепроверяет сессию при возврате приложения на передний план.
     *
     * Не чаще одного раза в пять минут, чтобы не создавать лишнюю сетевую
     * нагрузку. Ошибки обрабатываются репозиторием: при наличии свежего кэша
     * каталог остаётся доступен в офлайн-режиме.
     */
    fun onAppResumed() {
        val now = clock()
        if (now - lastSilentRefreshAtMillis < SILENT_REFRESH_INTERVAL_MILLIS) return
        lastSilentRefreshAtMillis = now
        viewModelScope.launch {
            repository.refreshSessionSilently()
        }
    }

    companion object {
        private const val SILENT_REFRESH_INTERVAL_MILLIS = 5L * 60L * 1000L

        fun factory(repository: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { AuthViewModel(repository) }
        }
    }
}

private fun SignInResult.toLoginError(): LoginError = when (this) {
    SignInResult.Success -> error("Success is not a login error")
    SignInResult.InvalidPhone -> LoginError.INVALID_PHONE
    SignInResult.PasswordRequired -> LoginError.PASSWORD_REQUIRED
    SignInResult.InvalidCredentials -> LoginError.INVALID_CREDENTIALS
    SignInResult.AccountInactive -> LoginError.ACCOUNT_INACTIVE
    SignInResult.AccountNotFound -> LoginError.ACCOUNT_NOT_FOUND
    SignInResult.AccountNotConfirmed -> LoginError.ACCOUNT_NOT_CONFIRMED
    SignInResult.PhoneLoginDisabled -> LoginError.PHONE_LOGIN_DISABLED
    SignInResult.ConfigurationMissing -> LoginError.CONFIGURATION_MISSING
    SignInResult.TooManyAttempts -> LoginError.TOO_MANY_ATTEMPTS
    SignInResult.NetworkError -> LoginError.NETWORK
    SignInResult.ServerError -> LoginError.SERVER
}
