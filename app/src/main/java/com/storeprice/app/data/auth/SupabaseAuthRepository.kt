package com.storeprice.app.data.auth

import android.util.Log
import com.storeprice.app.core.PhoneNumber
import com.storeprice.app.domain.auth.AuthState
import com.storeprice.app.domain.auth.AuthUnavailableReason
import com.storeprice.app.domain.auth.SignInResult
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Реализация AuthRepository поверх Supabase Auth и PostgREST.
 *
 * После каждого входа и обновления сессии профиль читается с сервера. Если
 * профиль неактивен или отсутствует, локальная сессия удаляется, поэтому
 * деактивированный сотрудник не попадёт в каталог. При временной сетевой или
 * серверной ошибке используется свежий кэш профиля, и каталог открывается в
 * офлайн-режиме.
 *
 * Проверка профиля при интерактивном входе выполняется ровно один раз: либо
 * коллектором [AuthBackend.sessionEvents], либо ожиданием его результата в
 * [signIn]. Это защищает от гонки, когда коллектор успевает очистить сессию
 * неактивного сотрудника до возврата из `signInWith`.
 */
internal class SupabaseAuthRepository(
    private val backend: AuthBackend,
    profileCache: CachedProfileStore,
    private val profileFetcher: ProfileFetcher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val beforeSignOut: suspend () -> Unit = {},
) : AuthRepository {

    private val profileCheckMutex = Mutex()
    private val sessionController = AuthSessionController(profileCache)
    private val interactiveSignIn = AtomicReference<CompletableDeferred<ProfileCheck>?>(null)

    override val state: StateFlow<AuthState> = sessionController.state

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            backend.sessionEvents.collect { event ->
                onSessionEvent(event)
            }
        }
    }

    override suspend fun signIn(phone: String, password: String): SignInResult {
        val normalizedPhone = PhoneNumber.normalize(phone) ?: return SignInResult.InvalidPhone
        if (password.isBlank()) return SignInResult.PasswordRequired

        val check = CompletableDeferred<ProfileCheck>()
        interactiveSignIn.set(check)
        return try {
            backend.signInWithPassword(normalizedPhone, password)
            // Не читаем currentSessionOrNull() после signInWith: коллектор мог
            // уже проверить профиль и очистить сессию неактивного сотрудника.
            check.await().toSignInResult()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: AuthRestException) {
            mapAuthError(error)
        } catch (error: RestException) {
            if (error.statusCode >= 500) SignInResult.ServerError else SignInResult.InvalidCredentials
        } catch (error: HttpRequestException) {
            Log.w(TAG, "Supabase Auth is unreachable", error)
            SignInResult.NetworkError
        } catch (error: HttpRequestTimeoutException) {
            Log.w(TAG, "Supabase Auth request timed out", error)
            SignInResult.NetworkError
        } catch (error: Exception) {
            Log.e(TAG, "Unexpected sign-in failure", error)
            SignInResult.ServerError
        } finally {
            interactiveSignIn.compareAndSet(check, null)
        }
    }

    override suspend fun signOut() {
        try {
            beforeSignOut()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Could not deactivate the device token before sign-out", error)
        }
        runCatching { backend.signOut() }
        runCatching { backend.clearSession() }
        sessionController.onSignedOut()
    }

    override suspend fun retrySession() {
        sessionController.onInitializing()
        val currentSession = backend.currentSession()
        if (currentSession != null) {
            validateSession(currentSession)
            return
        }

        val storedUserId = storedSessionUserId()
        try {
            if (backend.loadFromStorage()) {
                val loadedSession = backend.currentSession()
                if (loadedSession != null) {
                    validateSession(loadedSession)
                } else if (storedUserId != null) {
                    sessionController.onTransientFailure(
                        AuthUnavailableReason.NETWORK,
                        storedUserId,
                    )
                } else {
                    sessionController.onUnauthorized()
                }
            } else {
                sessionController.onUnauthorized()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Could not reload the stored session", error)
            if (storedUserId != null) {
                sessionController.onTransientFailure(AuthUnavailableReason.NETWORK, storedUserId)
            } else {
                sessionController.onUnavailable(AuthUnavailableReason.NETWORK)
            }
        }
    }

    override suspend fun refreshSessionSilently() {
        // Если SDK уже обновляет сессию после RefreshFailure, currentSession()
        // вернёт null; его собственный retry продолжится без экрана загрузки.
        val currentSession = backend.currentSession() ?: return
        validateSession(currentSession)
    }

    private suspend fun onSessionEvent(event: AuthSessionEvent) {
        when (event) {
            AuthSessionEvent.Initializing -> {
                sessionController.onInitializing()
            }

            AuthSessionEvent.NotAuthenticated -> {
                if (sessionController.state.value !is AuthState.SignedOut) {
                    sessionController.onSignedOut()
                }
            }

            is AuthSessionEvent.Authenticated -> {
                validateSession(event.session)
            }

            is AuthSessionEvent.RefreshFailure -> {
                sessionController.onTransientFailure(event.reason, currentOrStoredUserId())
            }
        }
    }

    /**
     * Проверяет профиль под общим мьютексом и сообщает результат интерактивному
     * входу, если он ждёт. [interactiveSignIn] читается до захвата мьютекса:
     * проверка, начатая до входа, не должна завершать новый Deferred.
     */
    private suspend fun validateSession(session: AuthSessionSnapshot): ProfileCheck {
        val interactive = interactiveSignIn.get()
        val result = profileCheckMutex.withLock {
            try {
                checkProfile(session)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e(TAG, "Unexpected profile check failure", error)
                sessionController.onUnavailable(AuthUnavailableReason.SERVER)
                ProfileCheck.Unavailable(AuthUnavailableReason.SERVER)
            }
        }
        interactive?.complete(result)
        return result
    }

    private suspend fun checkProfile(session: AuthSessionSnapshot): ProfileCheck {
        val userId = session.userId
        if (userId == null) {
            clearLocalSession()
            sessionController.onUnauthorized()
            return ProfileCheck.Missing
        }

        return when (val fetched = profileFetcher.fetch(userId, session.phone)) {
            is ProfileFetchResult.Found -> {
                if (!fetched.profile.isActive) {
                    clearLocalSession()
                    sessionController.onInactive()
                    ProfileCheck.Inactive
                } else {
                    sessionController.onActive(fetched.profile)
                    ProfileCheck.Active
                }
            }

            ProfileFetchResult.Missing -> {
                clearLocalSession()
                sessionController.onMissing()
                ProfileCheck.Missing
            }

            ProfileFetchResult.Unauthorized -> {
                clearLocalSession()
                sessionController.onUnauthorized()
                ProfileCheck.Missing
            }

            is ProfileFetchResult.Failed -> {
                if (fetched.allowCachedProfile) {
                    transientProfileCheck(fetched.reason, userId)
                } else {
                    sessionController.onUnavailable(fetched.reason)
                    ProfileCheck.Unavailable(fetched.reason)
                }
            }
        }
    }

    private fun ProfileCheck.toSignInResult(): SignInResult = when (this) {
        ProfileCheck.Active,
        ProfileCheck.Offline -> SignInResult.Success

        ProfileCheck.Inactive -> SignInResult.AccountInactive
        ProfileCheck.Missing -> SignInResult.AccountNotFound
        is ProfileCheck.Unavailable -> when (reason) {
            AuthUnavailableReason.NETWORK -> SignInResult.NetworkError
            AuthUnavailableReason.SERVER -> SignInResult.ServerError
        }
    }

    private fun transientProfileCheck(
        reason: AuthUnavailableReason,
        userId: String,
    ): ProfileCheck = if (sessionController.onTransientFailure(reason, userId)) {
        ProfileCheck.Offline
    } else {
        ProfileCheck.Unavailable(reason)
    }

    private suspend fun currentOrStoredUserId(): String? =
        backend.currentSession()?.userId ?: storedSessionUserId()

    private suspend fun storedSessionUserId(): String? = try {
        backend.storedSession()?.userId
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Log.w(TAG, "Could not read the stored session", error)
        null
    }

    private suspend fun clearLocalSession() {
        runCatching { backend.clearSession() }
    }

    private fun mapAuthError(error: AuthRestException): SignInResult = when {
        error.errorCode == AuthErrorCode.PhoneProviderDisabled -> SignInResult.PhoneLoginDisabled
        error.errorCode == AuthErrorCode.PhoneNotConfirmed -> SignInResult.AccountNotConfirmed
        error.errorCode == AuthErrorCode.UserBanned -> SignInResult.AccountInactive
        error.errorCode == AuthErrorCode.OverRequestRateLimit -> SignInResult.TooManyAttempts
        error.error == "invalid_credentials" -> SignInResult.InvalidCredentials
        error.statusCode == 429 -> SignInResult.TooManyAttempts
        error.statusCode >= 500 -> SignInResult.ServerError
        else -> SignInResult.InvalidCredentials
    }

    private sealed interface ProfileCheck {
        data object Active : ProfileCheck
        data object Offline : ProfileCheck
        data object Inactive : ProfileCheck
        data object Missing : ProfileCheck
        data class Unavailable(val reason: AuthUnavailableReason) : ProfileCheck
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
