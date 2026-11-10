package com.storeprice.app.ui.auth

import com.storeprice.app.data.auth.AuthRepository
import com.storeprice.app.domain.auth.AuthState
import com.storeprice.app.domain.auth.SignInResult
import com.storeprice.app.domain.model.EmployeeProfile
import com.storeprice.app.domain.model.EmployeeRole
import com.storeprice.app.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AuthViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `invalid phone is rejected before repository call`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)

        viewModel.onPhoneChange("123")
        viewModel.onPasswordChange("secret")
        viewModel.signIn()

        assertEquals(LoginError.INVALID_PHONE, viewModel.uiState.value.loginError)
        assertNull(repository.lastPhone)
        assertFalse(viewModel.uiState.value.isSubmitting)
    }

    @Test
    fun `empty password is rejected before repository call`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)

        viewModel.onPhoneChange("+7 700 123 45 67")
        viewModel.signIn()

        assertEquals(LoginError.PASSWORD_REQUIRED, viewModel.uiState.value.loginError)
        assertNull(repository.lastPhone)
    }

    @Test
    fun `phone is normalized before sign in and password is cleared on success`() = runTest {
        val employee = EmployeeProfile(
            id = "user-id",
            displayName = "Администратор",
            phone = "+77001234567",
            role = EmployeeRole.ADMIN,
            isActive = true,
        )
        val repository = FakeAuthRepository(
            signedInState = AuthState.SignedIn(employee),
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onPhoneChange("8 (700) 123-45-67")
        viewModel.onPasswordChange("secret")
        viewModel.signIn()

        assertEquals("+77001234567", repository.lastPhone)
        assertEquals("secret", repository.lastPassword)
        assertEquals("", viewModel.uiState.value.password)
        assertEquals(AuthState.SignedIn(employee), viewModel.uiState.value.authState)
        assertNull(viewModel.uiState.value.loginError)
    }

    @Test
    fun `invalid credentials are shown on the form`() = runTest {
        val repository = FakeAuthRepository(
            signInResult = SignInResult.InvalidCredentials,
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onPhoneChange("+7 700 123 45 67")
        viewModel.onPasswordChange("wrong")
        viewModel.signIn()

        assertEquals(LoginError.INVALID_CREDENTIALS, viewModel.uiState.value.loginError)
        assertFalse(viewModel.uiState.value.isSubmitting)
    }

    @Test
    fun `inactive account result is mapped to a readable error`() = runTest {
        val repository = FakeAuthRepository(
            signInResult = SignInResult.AccountInactive,
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onPhoneChange("+7 700 123 45 67")
        viewModel.onPasswordChange("secret")
        viewModel.signIn()

        assertEquals(LoginError.ACCOUNT_INACTIVE, viewModel.uiState.value.loginError)
    }

    @Test
    fun `missing profile result is mapped to a readable error`() = runTest {
        val repository = FakeAuthRepository(
            signInResult = SignInResult.AccountNotFound,
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onPhoneChange("+7 700 123 45 67")
        viewModel.onPasswordChange("secret")
        viewModel.signIn()

        assertEquals(LoginError.ACCOUNT_NOT_FOUND, viewModel.uiState.value.loginError)
    }

    @Test
    fun `editing phone clears previous login error`() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)

        viewModel.onPhoneChange("123")
        viewModel.onPasswordChange("secret")
        viewModel.signIn()
        viewModel.onPhoneChange("+7 700 123 45 67")

        assertNull(viewModel.uiState.value.loginError)
    }

    @Test
    fun `sign out delegates to repository and clears password`() = runTest {
        val repository = FakeAuthRepository(
            signedInState = AuthState.SignedIn(
                EmployeeProfile(
                    id = "user-id",
                    displayName = "Сотрудник",
                    phone = null,
                    role = EmployeeRole.EMPLOYEE,
                    isActive = true,
                ),
            ),
            initialState = AuthState.SignedIn(
                EmployeeProfile(
                    id = "user-id",
                    displayName = "Сотрудник",
                    phone = null,
                    role = EmployeeRole.EMPLOYEE,
                    isActive = true,
                ),
            ),
        )
        val viewModel = AuthViewModel(repository)
        viewModel.onPasswordChange("secret")

        viewModel.signOut()

        assertEquals(1, repository.signOutCalls)
        assertEquals("", viewModel.uiState.value.password)
    }

    private class FakeAuthRepository(
        initialState: AuthState = AuthState.SignedOut(),
        private val signedInState: AuthState? = null,
        var signInResult: SignInResult = SignInResult.Success,
    ) : AuthRepository {

        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<AuthState> = mutableState.asStateFlow()

        var lastPhone: String? = null
            private set
        var lastPassword: String? = null
            private set
        var signOutCalls: Int = 0
            private set
        var retryCalls: Int = 0
            private set

        override suspend fun signIn(phone: String, password: String): SignInResult {
            lastPhone = phone
            lastPassword = password
            if (signInResult == SignInResult.Success && signedInState != null) {
                mutableState.value = signedInState
            }
            return signInResult
        }

        override suspend fun signOut() {
            signOutCalls += 1
            mutableState.value = AuthState.SignedOut()
        }

        override suspend fun retrySession() {
            retryCalls += 1
        }
    }
}
