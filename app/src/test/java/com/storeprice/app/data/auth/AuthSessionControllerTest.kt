package com.storeprice.app.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.storeprice.app.domain.auth.AuthNotice
import com.storeprice.app.domain.auth.AuthState
import com.storeprice.app.domain.auth.AuthUnavailableReason
import com.storeprice.app.domain.model.EmployeeProfile
import com.storeprice.app.domain.model.EmployeeRole
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthSessionControllerTest {

    private var now = 1_000_000L
    private lateinit var store: CachedProfileStore
    private lateinit var controller: AuthSessionController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        now = 1_000_000L
        store = CachedProfileStore(context, now = { now })
        store.clear()
        controller = AuthSessionController(store, now = { now })
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun `network failure with fresh cache signs in offline`() {
        controller.onActive(employee())
        now += TimeUnit.HOURS.toMillis(2)

        val usedCache = controller.onTransientFailure(
            AuthUnavailableReason.NETWORK,
            USER_ID,
        )

        assertTrue(usedCache)
        val state = signedInState()
        assertTrue(state.isOffline)
        assertEquals(USER_ID, state.employee.id)
        assertEquals("Сотрудник", state.employee.displayName)
        assertEquals(EmployeeRole.EMPLOYEE, state.employee.role)
        // Телефон в кэше не храним: он нужен только для отображения имени и роли.
        assertNull(state.employee.phone)
    }

    @Test
    fun `network failure without cache stays unavailable`() {
        val usedCache = controller.onTransientFailure(
            AuthUnavailableReason.NETWORK,
            USER_ID,
        )

        assertFalse(usedCache)
        assertEquals(
            AuthState.Unavailable(AuthUnavailableReason.NETWORK),
            controller.state.value,
        )
    }

    @Test
    fun `cache older than thirty days stays unavailable`() {
        controller.onActive(employee())
        now += TimeUnit.DAYS.toMillis(30) + 1

        val usedCache = controller.onTransientFailure(
            AuthUnavailableReason.NETWORK,
            USER_ID,
        )

        assertFalse(usedCache)
        assertEquals(
            AuthState.Unavailable(AuthUnavailableReason.NETWORK),
            controller.state.value,
        )
        assertNull(store.load())
    }

    @Test
    fun `inactive profile clears cache`() {
        controller.onActive(employee())

        controller.onInactive()

        assertEquals(
            AuthState.SignedOut(AuthNotice.ACCOUNT_INACTIVE),
            controller.state.value,
        )
        assertNull(store.load())
    }

    @Test
    fun `missing profile clears cache`() {
        controller.onActive(employee())

        controller.onMissing()

        assertEquals(
            AuthState.SignedOut(AuthNotice.ACCOUNT_NOT_FOUND),
            controller.state.value,
        )
        assertNull(store.load())
    }

    @Test
    fun `unauthorized response clears cache`() {
        controller.onActive(employee())

        controller.onUnauthorized()

        assertEquals(
            AuthState.SignedOut(AuthNotice.SESSION_EXPIRED),
            controller.state.value,
        )
        assertNull(store.load())
    }

    @Test
    fun `sign out clears cache`() {
        controller.onActive(employee())

        controller.onSignedOut()

        assertEquals(AuthState.SignedOut(), controller.state.value)
        assertNull(store.load())
    }

    @Test
    fun `cache of another user is ignored and cleared`() {
        controller.onActive(employee(id = "user-a"))

        val usedCache = controller.onTransientFailure(
            AuthUnavailableReason.NETWORK,
            "user-b",
        )

        assertFalse(usedCache)
        assertEquals(
            AuthState.Unavailable(AuthUnavailableReason.NETWORK),
            controller.state.value,
        )
        assertNull(store.load())
    }

    @Test
    fun `successful check after offline failure clears offline flag`() {
        controller.onActive(employee())
        now += TimeUnit.HOURS.toMillis(1)
        assertTrue(controller.onTransientFailure(AuthUnavailableReason.NETWORK, USER_ID))
        assertTrue(signedInState().isOffline)

        controller.onActive(employee())

        val state = signedInState()
        assertFalse(state.isOffline)
        assertEquals(employee(), state.employee)
    }

    private fun signedInState(): AuthState.SignedIn =
        controller.state.value as AuthState.SignedIn

    private fun employee(id: String = USER_ID): EmployeeProfile = EmployeeProfile(
        id = id,
        displayName = "Сотрудник",
        phone = "+77001234567",
        role = EmployeeRole.EMPLOYEE,
        isActive = true,
    )

    private companion object {
        const val USER_ID = "user-1"
    }
}
