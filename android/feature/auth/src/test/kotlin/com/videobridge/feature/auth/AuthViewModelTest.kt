package com.videobridge.feature.auth

import app.cash.turbine.test
import com.videobridge.core.common.AppError
import com.videobridge.core.common.AppResult
import com.videobridge.core.data.auth.AuthRepository
import com.videobridge.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeAuthRepository : AuthRepository {
        var next: AppResult<Unit> = AppResult.Success(Unit)
        var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String>()

        override suspend fun register(name: String, phone: String, password: String): AppResult<Unit> {
            calls += "register|$name|$phone|$password"
            gate?.await()
            return next
        }

        override suspend fun login(phone: String, password: String): AppResult<Unit> {
            calls += "login|$phone|$password"
            gate?.await()
            return next
        }

        override suspend fun logout() = Unit
    }

    private val repository = FakeAuthRepository()
    private val viewModel = AuthViewModel(repository)

    private fun fill(name: String = "Janak", phone: String = "9876543210", password: String = "1") {
        viewModel.onNameChange(name)
        viewModel.onPhoneChange(phone)
        viewModel.onPasswordChange(password)
    }

    @Test
    fun `creating an account needs name, number and password - all three`() {
        viewModel.onModeChange(AuthMode.CREATE_ACCOUNT)
        assertFalse(viewModel.uiState.value.canSubmit)
        fill(name = "")
        assertFalse(viewModel.uiState.value.canSubmit)
        fill(name = "   ")
        assertFalse(viewModel.uiState.value.canSubmit)
        fill(phone = "")
        assertFalse(viewModel.uiState.value.canSubmit)
        fill(password = "")
        assertFalse(viewModel.uiState.value.canSubmit)

        fill()
        assertTrue(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `a one-character password is accepted`() {
        viewModel.onModeChange(AuthMode.CREATE_ACCOUNT)
        fill(password = "1")

        viewModel.submit()

        assertEquals(listOf("register|Janak|9876543210|1"), repository.calls)
    }

    @Test
    fun `the screen opens on sign-in, which needs only number and password`() {
        assertEquals(AuthMode.SIGN_IN, viewModel.uiState.value.mode)
        fill(name = "")

        assertTrue(viewModel.uiState.value.canSubmit)
        viewModel.submit()

        assertEquals(listOf("login|9876543210|1"), repository.calls)
    }

    @Test
    fun `nothing is sent while a field is missing`() {
        fill(password = "")

        viewModel.submit()

        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `the phone field keeps only digits and plus`() {
        viewModel.onPhoneChange("+91 98765-43210 abc")

        assertEquals("+919876543210", viewModel.uiState.value.phone)
    }

    @Test
    fun `the eye toggles password visibility`() {
        assertFalse(viewModel.uiState.value.passwordVisible)
        viewModel.onTogglePasswordVisible()
        assertTrue(viewModel.uiState.value.passwordVisible)
        viewModel.onTogglePasswordVisible()
        assertFalse(viewModel.uiState.value.passwordVisible)
    }

    @Test
    fun `submitting shows progress, blocks a second tap, then clears the password on success`() = runTest {
        repository.gate = CompletableDeferred()
        fill()

        viewModel.uiState.test {
            assertFalse(awaitItem().submitting)
            viewModel.submit()
            val submitting = awaitItem()
            assertTrue(submitting.submitting)
            assertFalse(submitting.canSubmit)
            viewModel.submit()

            repository.gate?.complete(Unit)
            val done = awaitItem()
            assertFalse(done.submitting)
            assertEquals("", done.password)
            assertNull(done.error)
        }
        assertEquals(1, repository.calls.size)
    }

    @Test
    fun `each failure maps to its own message, and typing clears it`() {
        val cases =
            listOf(
                AppError.Network to AuthError.Unreachable,
                AppError.Api("INVALID_CREDENTIALS", "x", 401) to AuthError.WrongCredentials,
                AppError.Api("PHONE_ALREADY_REGISTERED", "x", 409) to AuthError.PhoneAlreadyRegistered,
                AppError.Api("VALIDATION_FAILED", "x", 400) to AuthError.InvalidPhone,
                AppError.Api("RATE_LIMITED", "x", 429) to AuthError.TooManyAttempts,
                AppError.Api("USER_SUSPENDED", "This account is suspended.", 403) to
                    AuthError.Message("This account is suspended."),
                AppError.Api("UNKNOWN", "x", 502) to AuthError.Unexpected,
                AppError.Unknown(IllegalStateException()) to AuthError.Unexpected,
            )
        cases.forEach { (failure, expected) ->
            repository.next = AppResult.Failure(failure)
            fill()

            viewModel.submit()

            assertEquals(expected, viewModel.uiState.value.error)
            assertEquals("the password stays so the user can retry", "1", viewModel.uiState.value.password)
        }

        viewModel.onPasswordChange("12")
        assertNull(viewModel.uiState.value.error)
    }
}
