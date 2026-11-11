package com.storeprice.app.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.storeprice.app.domain.auth.AuthUnavailableReason
import com.storeprice.app.domain.auth.SignInResult
import com.storeprice.app.domain.model.EmployeeProfile
import com.storeprice.app.domain.model.EmployeeRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Проверяет вход, когда коллектор `sessionEvents` успевает обработать
 * `Authenticated` раньше, чем `signInWithPassword` возвращает управление.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupabaseAuthRepositoryTest {

    private lateinit var store: CachedProfileStore
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = CachedProfileStore(context)
        store.clear()
        // Unconfined выполняет коллектор прямо внутри signInWithPassword,
        // детерминированно воспроизводя порядок «коллектор первым».
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        store.clear()
    }

    @Test
    fun `inactive profile handled by collector returns AccountInactive`() = runTest {
        val backend = FakeAuthBackend()
        val (repository, fetcher) = repository(
            backend = backend,
            result = ProfileFetchResult.Found(employee(isActive = false)),
        )

        val result = repository.signIn(PHONE, PASSWORD)

        assertEquals(SignInResult.AccountInactive, result)
        assertEquals(1, fetcher.calls)
        assertEquals(0, backend.currentSessionCalls)
        assertNull(backend.signedInSession)
    }

    @Test
    fun `missing profile handled by collector returns AccountNotFound`() = runTest {
        val backend = FakeAuthBackend()
        val (repository, fetcher) = repository(
            backend = backend,
            result = ProfileFetchResult.Missing,
        )

        val result = repository.signIn(PHONE, PASSWORD)

        assertEquals(SignInResult.AccountNotFound, result)
        assertEquals(1, fetcher.calls)
        assertEquals(0, backend.currentSessionCalls)
        assertNull(backend.signedInSession)
    }

    @Test
    fun `network failure handled by collector returns NetworkError`() = runTest {
        val backend = FakeAuthBackend()
        val (repository, fetcher) = repository(
            backend = backend,
            result = ProfileFetchResult.Failed(
                reason = AuthUnavailableReason.NETWORK,
                allowCachedProfile = true,
            ),
        )

        val result = repository.signIn(PHONE, PASSWORD)

        assertEquals(SignInResult.NetworkError, result)
        assertEquals(1, fetcher.calls)
        assertEquals(0, backend.currentSessionCalls)
    }

    @Test
    fun `server failure handled by collector returns ServerError`() = runTest {
        val backend = FakeAuthBackend()
        val (repository, fetcher) = repository(
            backend = backend,
            result = ProfileFetchResult.Failed(
                reason = AuthUnavailableReason.SERVER,
                allowCachedProfile = true,
            ),
        )

        val result = repository.signIn(PHONE, PASSWORD)

        assertEquals(SignInResult.ServerError, result)
        assertEquals(1, fetcher.calls)
        assertEquals(0, backend.currentSessionCalls)
    }

    private fun repository(
        backend: FakeAuthBackend,
        result: ProfileFetchResult,
    ): Pair<SupabaseAuthRepository, RecordingProfileFetcher> {
        val fetcher = RecordingProfileFetcher(result)
        val repository = SupabaseAuthRepository(
            backend = backend,
            profileCache = store,
            profileFetcher = fetcher,
            scope = scope,
        )
        return repository to fetcher
    }

    private fun employee(isActive: Boolean): EmployeeProfile = EmployeeProfile(
        id = USER_ID,
        displayName = "Сотрудник",
        phone = null,
        role = EmployeeRole.EMPLOYEE,
        isActive = isActive,
    )

    private class FakeAuthBackend : AuthBackend {

        private val events = MutableStateFlow<AuthSessionEvent>(AuthSessionEvent.Initializing)

        override val sessionEvents: Flow<AuthSessionEvent> = events

        var signedInSession: AuthSessionSnapshot? = null
            private set

        var currentSessionCalls: Int = 0
            private set

        override suspend fun signInWithPassword(phone: String, password: String) {
            val session = AuthSessionSnapshot(userId = USER_ID, phone = phone)
            signedInSession = session
            // Коллектор получает событие и проверяет профиль до возврата из
            // этого метода — ровно тот порядок, который ломал старый signIn.
            events.value = AuthSessionEvent.Authenticated(session)
        }

        override suspend fun currentSession(): AuthSessionSnapshot? {
            currentSessionCalls += 1
            return signedInSession
        }

        override suspend fun storedSession(): AuthSessionSnapshot? = signedInSession

        override suspend fun loadFromStorage(): Boolean = false

        override suspend fun clearSession() {
            signedInSession = null
        }

        override suspend fun signOut() {
            signedInSession = null
        }
    }

    private class RecordingProfileFetcher(
        private val result: ProfileFetchResult,
    ) : ProfileFetcher {

        var calls: Int = 0
            private set

        override suspend fun fetch(userId: String, phone: String?): ProfileFetchResult {
            calls += 1
            return result
        }
    }

    private companion object {
        const val USER_ID = "user-1"
        const val PHONE = "+7 700 123 45 67"
        const val PASSWORD = "secret"
    }
}
