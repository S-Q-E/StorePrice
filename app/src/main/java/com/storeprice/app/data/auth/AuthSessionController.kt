package com.storeprice.app.data.auth

import com.storeprice.app.domain.auth.AuthNotice
import com.storeprice.app.domain.auth.AuthState
import com.storeprice.app.domain.auth.AuthUnavailableReason
import com.storeprice.app.domain.model.EmployeeProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Принимает решения о состоянии входа и управляет офлайн-кэшем профиля.
 *
 * Вынесен из [SupabaseAuthRepository], чтобы правила кэша можно было проверить
 * unit-тестами без сетевого клиента. Кэш используется только при временной
 * сетевой или серверной ошибке; ответы 401 и изменения профиля его очищают.
 */
internal class AuthSessionController(
    private val profileCache: CachedProfileStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow<AuthState>(AuthState.Initializing)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun onInitializing() {
        _state.value = AuthState.Initializing
    }

    /** Профиль успешно проверен на сервере: кэш обновлён, офлайн-флаг снят. */
    fun onActive(profile: EmployeeProfile) {
        profileCache.save(profile)
        _state.value = AuthState.SignedIn(profile, isOffline = false)
    }

    fun onInactive() {
        profileCache.clear()
        _state.value = AuthState.SignedOut(AuthNotice.ACCOUNT_INACTIVE)
    }

    fun onMissing() {
        profileCache.clear()
        _state.value = AuthState.SignedOut(AuthNotice.ACCOUNT_NOT_FOUND)
    }

    /** Сервер ответил 401: сессия недействительна, кэш больше не нужен. */
    fun onUnauthorized() {
        profileCache.clear()
        _state.value = AuthState.SignedOut(AuthNotice.SESSION_EXPIRED)
    }

    /** Пользователь вышел или локальная сессия удалена. */
    fun onSignedOut() {
        profileCache.clear()
        _state.value = AuthState.SignedOut()
    }

    /** Ошибка, при которой сервер не дал проверить профиль. */
    fun onUnavailable(reason: AuthUnavailableReason) {
        _state.value = AuthState.Unavailable(reason)
    }

    /**
     * Пытается войти в каталог по свежему кэшу при сетевой или серверной ошибке.
     *
     * @param userId идентификатор пользователя из текущей сессии. Если он
     * неизвестен, доверять кэшу нельзя.
     * @return `true`, если состояние переведено в [AuthState.SignedIn] с
     * `isOffline = true`.
     */
    fun onTransientFailure(reason: AuthUnavailableReason, userId: String?): Boolean {
        if (userId == null) {
            _state.value = AuthState.Unavailable(reason)
            return false
        }

        val cached = profileCache.load()
        val nowMillis = now()
        if (cached != null && cached.isFreshFor(userId, nowMillis)) {
            _state.value = AuthState.SignedIn(
                employee = cached.toEmployeeProfile(),
                isOffline = true,
            )
            return true
        }

        if (cached != null) {
            profileCache.clear()
        }
        _state.value = AuthState.Unavailable(reason)
        return false
    }

    private fun CachedProfile.isFreshFor(userId: String, nowMillis: Long): Boolean =
        id == userId &&
            isActive &&
            cachedAtMillis <= nowMillis &&
            nowMillis - cachedAtMillis <= OFFLINE_GRACE_MILLIS

    private companion object {
        const val OFFLINE_GRACE_DAYS = 30L
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        const val OFFLINE_GRACE_MILLIS = OFFLINE_GRACE_DAYS * MILLIS_PER_DAY
    }
}
