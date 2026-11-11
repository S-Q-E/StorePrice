package com.storeprice.app.data.auth

import android.util.Log
import com.storeprice.app.domain.auth.AuthUnavailableReason
import com.storeprice.app.domain.model.EmployeeProfile
import com.storeprice.app.domain.model.EmployeeRole
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Минимальные данные сессии, нужные для проверки профиля и офлайн-кэша. */
internal data class AuthSessionSnapshot(
    val userId: String?,
    val phone: String?,
)

/** События Auth, не зависящие от типов supabase-kt. */
internal sealed interface AuthSessionEvent {
    data object Initializing : AuthSessionEvent
    data object NotAuthenticated : AuthSessionEvent
    data class Authenticated(val session: AuthSessionSnapshot) : AuthSessionEvent
    data class RefreshFailure(val reason: AuthUnavailableReason) : AuthSessionEvent
}

/**
 * Тонкая обёртка над Supabase Auth.
 *
 * Нужна, чтобы сценарий входа можно было проверить unit-тестом с fake-реализацией,
 * не поднимая настоящий SupabaseClient.
 */
internal interface AuthBackend {
    val sessionEvents: Flow<AuthSessionEvent>

    suspend fun signInWithPassword(phone: String, password: String)
    suspend fun currentSession(): AuthSessionSnapshot?
    suspend fun storedSession(): AuthSessionSnapshot?
    suspend fun loadFromStorage(): Boolean
    suspend fun clearSession()
    suspend fun signOut()
}

/** Результат чтения профиля, уже разложенный по причинам ошибок. */
internal sealed interface ProfileFetchResult {
    data class Found(val profile: EmployeeProfile) : ProfileFetchResult
    data object Missing : ProfileFetchResult
    data object Unauthorized : ProfileFetchResult

    /**
     * @param allowCachedProfile разрешено ли использовать офлайн-кэш. Для сети и
     * 5xx — да, для остальных ошибок сервера — нет.
     */
    data class Failed(
        val reason: AuthUnavailableReason,
        val allowCachedProfile: Boolean,
    ) : ProfileFetchResult
}

/** Читает профиль сотрудника по id. */
internal fun interface ProfileFetcher {
    suspend fun fetch(userId: String, phone: String?): ProfileFetchResult
}

/** Обёртка над Supabase Auth для production. */
internal class SupabaseAuthBackend(
    private val supabase: SupabaseClient,
    private val sessionManager: SessionManager,
) : AuthBackend {

    override val sessionEvents: Flow<AuthSessionEvent> =
        supabase.auth.sessionStatus.map { status ->
            when (status) {
                SessionStatus.Initializing -> AuthSessionEvent.Initializing
                is SessionStatus.NotAuthenticated -> AuthSessionEvent.NotAuthenticated
                is SessionStatus.Authenticated -> {
                    AuthSessionEvent.Authenticated(status.session.toSnapshot())
                }

                is SessionStatus.RefreshFailure -> {
                    val reason = when (status.cause) {
                        is RefreshFailureCause.NetworkError -> AuthUnavailableReason.NETWORK
                        is RefreshFailureCause.InternalServerError -> AuthUnavailableReason.SERVER
                    }
                    AuthSessionEvent.RefreshFailure(reason)
                }
            }
        }

    override suspend fun signInWithPassword(phone: String, password: String) {
        supabase.auth.signInWith(Phone) {
            this.phone = phone
            this.password = password
        }
    }

    override suspend fun currentSession(): AuthSessionSnapshot? =
        supabase.auth.currentSessionOrNull()?.toSnapshot()

    override suspend fun storedSession(): AuthSessionSnapshot? =
        sessionManager.loadSession()?.toSnapshot()

    override suspend fun loadFromStorage(): Boolean =
        supabase.auth.loadFromStorage()

    override suspend fun clearSession() {
        supabase.auth.clearSession()
    }

    override suspend fun signOut() {
        supabase.auth.signOut()
    }

    private fun UserSession.toSnapshot(): AuthSessionSnapshot = AuthSessionSnapshot(
        userId = user?.id,
        phone = user?.phone,
    )
}

/** Читает профиль через PostgREST и переводит исключения в [ProfileFetchResult]. */
internal class SupabaseProfileFetcher(
    private val supabase: SupabaseClient,
) : ProfileFetcher {

    override suspend fun fetch(userId: String, phone: String?): ProfileFetchResult {
        return try {
            val row = supabase.postgrest
                .from(TABLE_PROFILES)
                .select(
                    Columns.list("id", "display_name", "role", "is_active"),
                ) {
                    filter { eq("id", userId) }
                    limit(1)
                }
                .decodeSingleOrNull<ProfileRow>()

            if (row == null) {
                ProfileFetchResult.Missing
            } else {
                ProfileFetchResult.Found(row.toDomain(phone))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: RestException) {
            if (error.statusCode == 401) {
                ProfileFetchResult.Unauthorized
            } else {
                Log.w(TAG, "Could not read the employee profile", error)
                ProfileFetchResult.Failed(
                    reason = AuthUnavailableReason.SERVER,
                    allowCachedProfile = error.statusCode >= 500,
                )
            }
        } catch (error: HttpRequestException) {
            Log.w(TAG, "Could not reach Supabase to read the profile", error)
            ProfileFetchResult.Failed(
                reason = AuthUnavailableReason.NETWORK,
                allowCachedProfile = true,
            )
        } catch (error: HttpRequestTimeoutException) {
            Log.w(TAG, "Profile request timed out", error)
            ProfileFetchResult.Failed(
                reason = AuthUnavailableReason.NETWORK,
                allowCachedProfile = true,
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unexpected profile check failure", error)
            ProfileFetchResult.Failed(
                reason = AuthUnavailableReason.SERVER,
                allowCachedProfile = false,
            )
        }
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
        const val TAG = "ProfileFetcher"
        const val TABLE_PROFILES = "profiles"
        const val ROLE_ADMIN = "admin"
    }
}
