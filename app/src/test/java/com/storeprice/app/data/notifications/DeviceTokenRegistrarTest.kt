package com.storeprice.app.data.notifications

import com.storeprice.app.data.remote.DeviceTokenRemote
import com.storeprice.app.domain.auth.AuthState
import com.storeprice.app.domain.model.EmployeeProfile
import com.storeprice.app.domain.model.EmployeeRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceTokenRegistrarTest {

    private val employee = EmployeeProfile(
        id = "user-1",
        displayName = "Сотрудник",
        phone = null,
        role = EmployeeRole.ADMIN,
        isActive = true,
    )

    @Test
    fun `registers token after an online sign in`() = runTest {
        val remote = FakeDeviceTokenRemote()
        val authState = MutableStateFlow<AuthState>(AuthState.SignedOut())
        val network = MutableStateFlow(true)
        val registrar = registrar(remote, authState, network)

        registrar.start()
        advanceUntilIdle()
        assertEquals(0, remote.registerCalls)

        authState.value = AuthState.SignedIn(employee)
        advanceUntilIdle()

        assertEquals(1, remote.registerCalls)
        assertEquals("fcm-token", remote.lastToken)
        assertEquals("device-1", remote.lastDeviceId)
    }

    @Test
    fun `does not register while offline and registers after recovery`() = runTest {
        val remote = FakeDeviceTokenRemote()
        val authState = MutableStateFlow<AuthState>(AuthState.SignedIn(employee))
        val network = MutableStateFlow(false)
        val registrar = registrar(remote, authState, network)

        registrar.start()
        advanceUntilIdle()
        assertEquals(0, remote.registerCalls)

        network.value = true
        advanceUntilIdle()

        assertEquals(1, remote.registerCalls)
    }

    @Test
    fun `token refresh registers when the session is active`() = runTest {
        val remote = FakeDeviceTokenRemote()
        val authState = MutableStateFlow<AuthState>(AuthState.SignedIn(employee))
        val network = MutableStateFlow(true)
        val registrar = registrar(remote, authState, network)

        registrar.onNewToken("refreshed-token")
        advanceUntilIdle()

        assertEquals(1, remote.registerCalls)
        assertEquals("refreshed-token", remote.lastToken)
    }

    @Test
    fun `unregister deactivates the device token`() = runTest {
        val remote = FakeDeviceTokenRemote()
        val authState = MutableStateFlow<AuthState>(AuthState.SignedIn(employee))
        val network = MutableStateFlow(true)
        val registrar = registrar(remote, authState, network)

        registrar.unregister()

        assertEquals(1, remote.unregisterCalls)
        assertEquals("device-1", remote.lastDeviceId)
    }

    private fun kotlinx.coroutines.test.TestScope.registrar(
        remote: FakeDeviceTokenRemote,
        authState: MutableStateFlow<AuthState>,
        network: MutableStateFlow<Boolean>,
    ) = DeviceTokenRegistrar(
        remote = remote,
        tokenSource = FcmTokenSource { "fcm-token" },
        deviceId = "device-1",
        authState = authState,
        networkAvailable = network,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
    )

    private class FakeDeviceTokenRemote : DeviceTokenRemote {
        var registerCalls = 0
        var unregisterCalls = 0
        var lastToken: String? = null
        var lastDeviceId: String? = null

        override suspend fun register(fcmToken: String, deviceId: String) {
            registerCalls += 1
            lastToken = fcmToken
            lastDeviceId = deviceId
        }

        override suspend fun unregister(deviceId: String) {
            unregisterCalls += 1
            lastDeviceId = deviceId
        }
    }
}
