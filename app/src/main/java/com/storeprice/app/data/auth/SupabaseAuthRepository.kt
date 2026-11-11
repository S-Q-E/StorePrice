package com.storeprice.app.data.auth

import android.util.Log
import com.storeprice.app.core.PhoneNumber
import com.storeprice.app.domain.auth.AuthState
import com.storeprice.app.domain.auth.AuthUnavailableReason
import com.storeprice.app.domain.auth.SignInResult
import com.storeprice.app.domain.model.EmployeeProfile
import com.storeprice.app.domain.model.EmployeeRole
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Phone
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Реализация AuthRepository поверх Supabase Auth и PostgREST.
 *
 * После каждого входа и обновления сессии профиль читается с сервера. Если
 * профиль неактивен или отсутствует, локальная сессия удаляется, поэтому
 * деактивированный сотрудник не попадёт в каталог. При временной сетевой или
 * серверной ошибке используется свежий кэш профиля, и каталог открывается в
 * офлайн-режиме.
 */
class SupabaseAuthRepository(
    private val supabase: SupabaseClient,
    profileCache: CachedProfileStore,
    private val sessionManager: SessionManager,
) : AuthRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val profileCheckMutex = Mutex()
    private val sessionController = AuthSessionController(profileCache)

    override val state: StateFlow<AuthState> = sessionController.state

    init {
        scope.launch {
            supabase.auth.sessionStatus.collect { status ->
                onSessionStatus(status)
            }
        }
    }

    override suspend fun signIn(phone: String, password: String): SignInResult {
        val normalizedPhone = PhoneNumber.normalize(phone) ?: return SignInResult.InvalidPhone
        if (password.isBlank()) return SignInResult.PasswordRequired

        return try {
            supabase.auth.signInWith(Phone) {
                this.phone = normalizedPhone
                this.password = password
            }

            val session = supabase.auth.currentSessionOrNull()
                ?: return SignInResult.ServerError

            when (val check = validateSession(session)) {
                ProfileCheck.Active,
                ProfileCheck.Offline -> SignInResult.Success

                ProfileCheck.Inactive -> SignInResult.AccountInactive
                ProfileCheck.Missing -> SignInResult.AccountNotFound
                is ProfileCheck.Unavailable -> when (check.reason) {
                    AuthUnavailableReason.NETWORK -> SignInResult.NetworkError
                    AuthUnavailableReason.SERVER -> SignInResult.ServerError
                }
            }
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
        }
    }

    override suspend fun signOut() {
        runCatching { supabase.auth.signOut() }
        runCatching { supabase.auth.clearSession() }
        sessionController.onSignedOut()
    }

    override suspend fun retrySession() {
        sessionController.onInitializing()
        val currentSession = supabase.auth.currentSessionOrNull()
        if (currentSession != null) {
            validateSession(currentSession)
            return
        }

        val storedUserId = storedSessionUserId()
        try {
            if (supabase.auth.loadFromStorage()) {
                val loadedSession = supabase.auth.currentSessionOrNull()
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
        // Если SDK уже обновляет сессию после RefreshFailure, currentSessionOrNull
        // вернёт null; его собственный retry продолжится без экрана загрузки.
        val currentSession = supabase.auth.currentSessionOrNull() ?: return
        validateSession(currentSession)
    }

    private suspend fun onSessionStatus(status: SessionStatus) {
        when (status) {
            SessionStatus.Initializing -> {
                sessionController.onInitializing()
            }

            is SessionStatus.NotAuthenticated -> {
                if (sessionController.state.value !is AuthState.SignedOut) {
                    sessionController.onSignedOut()
                }
            }

            is SessionStatus.Authenticated -> {
                validateSession(status.session)
            }

            is SessionStatus.RefreshFailure -> {
                val reason = when (status.cause) {
                    is RefreshFailureCause.NetworkError -> AuthUnavailableReason.NETWORK
                    is RefreshFailureCause.InternalServerError -> AuthUnavailableReason.SERVER
                }
                sessionController.onTransientFailure(reason, currentOrStoredUserId())
            }
        }
    }

    private suspend fun validateSession(session: UserSession): ProfileCheck =
        profileCheckMutex.withLock {
            val user = session.user
            if (user == null) {
                clearLocalSession()
                sessionController.onUnauthorized()
                return@withLock ProfileCheck.Missing
            }

            try {
                val profile = fetchProfile(user.id, user.phone)
                when {
                    profile == null -> {
                        clearLocalSession()
                        sessionController.onMissing()
                        ProfileCheck.Missing
                    }

                    !profile.isActive -> {
                        clearLocalSession()
                        sessionController.onInactive()
                        ProfileCheck.Inactive
                    }

                    else -> {
                        sessionController.onActive(profile)
                        ProfileCheck.Active
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: RestException) {
                if (error.statusCode == 401) {
                    clearLocalSession()
                    sessionController.onUnauthorized()
                    ProfileCheck.Missing
                } else {
                    Log.w(TAG, "Could not read the employee profile", error)
                    if (error.statusCode >= 500) {
                        transientProfileCheck(AuthUnavailableReason.SERVER, user.id)
                    } else {
                        sessionController.onUnavailable(AuthUnavailableReason.SERVER)
                        ProfileCheck.Unavailable(AuthUnavailableReason.SERVER)
                    }
                }
            } catch (error: HttpRequestException) {
                Log.w(TAG, "Could not reach Supabase to read the profile", error)
                transientProfileCheck(AuthUnavailableReason.NETWORK, user.id)
            } catch (error: HttpRequestTimeoutException) {
                Log.w(TAG, "Profile request timed out", error)
                transientProfileCheck(AuthUnavailableReason.NETWORK, user.id)
            } catch (error: Exception) {
                Log.e(TAG, "Unexpected profile check failure", error)
                sessionController.onUnavailable(AuthUnavailableReason.SERVER)
                ProfileCheck.Unavailable(AuthUnavailableReason.SERVER)
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

    private suspend fun fetchProfile(userId: String, phone: String?): EmployeeProfile? {
        val row = supabase.postgrest
            .from(TABLE_PROFILES)
            .select(
                Columns.list("id", "display_name", "role", "is_active"),
            ) {
                filter { eq("id", userId) }
                limit(1)
            }
            .decodeSingleOrNull<ProfileRow>()

        return row?.toDomain(phone)
    }

    private suspend fun currentOrStoredUserId(): String? =
        supabase.auth.currentSessionOrNull()?.user?.id ?: storedSessionUserId()

    private suspend fun storedSessionUserId(): String? = try {
        sessionManager.loadSession()?.user?.id
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Log.w(TAG, "Could not read the stored session", error)
        null
    }

    private suspend fun clearLocalSession() {
        runCatching { supabase.auth.clearSession() }
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

    @Serializable
    private data class ProfileRow(
        val id: String,
        @SerialName("display_name") val displayName: String,
        val role: String,
        @SerialName("is_active") val isActive: Boolean,
    ) {
        fun toDomain(phone: String?): EmployeeProfile = EmployeeProfile(
            id = id,
            displayName = displayName,
            phone = phone,
            role = if (role == ROLE_ADMIN) EmployeeRole.ADMIN else EmployeeRole.EMPLOYEE,
            isActive = isActive,
        )
    }

    private companion object {
        const val TAG = "AuthRepository"
        const val TABLE_PROFILES = "profiles"
        const val ROLE_ADMIN = "admin"
    }
}
