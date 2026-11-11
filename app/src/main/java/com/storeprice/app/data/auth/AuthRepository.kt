package com.storeprice.app.data.auth

import com.storeprice.app.domain.auth.AuthState
import com.storeprice.app.domain.auth.SignInResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Управляет сессией Supabase Auth и проверяет профиль сотрудника.
 *
 * Пароль не сохраняется приложением: Supabase Auth хранит только access/refresh
 * token, а профиль и роль всегда читаются с сервера.
 */
interface AuthRepository {
    val state: StateFlow<AuthState>

    /** Входит по номеру в формате E.164 и паролю. */
    suspend fun signIn(phone: String, password: String): SignInResult

    /** Завершает сессию и удаляет локальные токены, даже если сеть недоступна. */
    suspend fun signOut()

    /** Повторно загружает сессию и проверяет профиль после сетевой ошибки. */
    suspend fun retrySession()

    /**
     * Тихо перепроверяет сессию и профиль, не показывая экран загрузки.
     *
     * Используется при возврате приложения на передний план, чтобы снять
     * офлайн-режим после восстановления сети.
     */
    suspend fun refreshSessionSilently()
}
