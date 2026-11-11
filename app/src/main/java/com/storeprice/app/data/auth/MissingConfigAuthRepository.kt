package com.storeprice.app.data.auth

import com.storeprice.app.domain.auth.AuthNotice
import com.storeprice.app.domain.auth.AuthState
import com.storeprice.app.domain.auth.SignInResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Заглушка для сборки без локальных параметров Supabase.
 *
 * Приложение не падает при запуске, но экран входа сообщает, что настройка
 * отсутствует. Публичный URL и ключ задаются только в local.properties.
 */
class MissingConfigAuthRepository : AuthRepository {

    private val _state = MutableStateFlow<AuthState>(
        AuthState.SignedOut(AuthNotice.CONFIGURATION_MISSING),
    )
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    override suspend fun signIn(phone: String, password: String): SignInResult =
        SignInResult.ConfigurationMissing

    override suspend fun signOut() {
        _state.value = AuthState.SignedOut(AuthNotice.CONFIGURATION_MISSING)
    }

    override suspend fun retrySession() = Unit

    override suspend fun refreshSessionSilently() = Unit
}
