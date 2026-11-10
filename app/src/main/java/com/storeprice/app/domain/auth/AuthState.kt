package com.storeprice.app.domain.auth

import com.storeprice.app.domain.model.EmployeeProfile

/**
 * Состояние входа, которым управляет репозиторий авторизации.
 *
 * Экран входа показывается только в [SignedOut]; каталог — только в [SignedIn].
 */
sealed interface AuthState {

    /** Сессия загружается из локального хранилища или проверяется на сервере. */
    data object Initializing : AuthState

    /** Пользователь не авторизован. [notice] объясняет причину, если вход прервался. */
    data class SignedOut(val notice: AuthNotice? = null) : AuthState

    /** Сессия действительна, профиль активен. */
    data class SignedIn(val employee: EmployeeProfile) : AuthState

    /** Сессия есть, но профиль временно не удалось проверить. */
    data class Unavailable(val reason: AuthUnavailableReason) : AuthState
}

/** Причина, по которой пользователь оказался на экране входа. */
enum class AuthNotice {
    ACCOUNT_INACTIVE,
    ACCOUNT_NOT_FOUND,
    SESSION_EXPIRED,
    CONFIGURATION_MISSING,
}

/** Причина временной недоступности проверки сессии. */
enum class AuthUnavailableReason {
    NETWORK,
    SERVER,
}

/** Результат попытки входа по номеру телефона и паролю. */
sealed interface SignInResult {
    data object Success : SignInResult
    data object InvalidPhone : SignInResult
    data object PasswordRequired : SignInResult
    data object InvalidCredentials : SignInResult
    data object AccountInactive : SignInResult
    data object AccountNotFound : SignInResult
    data object AccountNotConfirmed : SignInResult
    data object PhoneLoginDisabled : SignInResult
    data object ConfigurationMissing : SignInResult
    data object TooManyAttempts : SignInResult
    data object NetworkError : SignInResult
    data object ServerError : SignInResult
}
