package com.videobridge.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videobridge.core.common.AppError
import com.videobridge.core.common.AppResult
import com.videobridge.core.data.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthMode { CREATE_ACCOUNT, SIGN_IN }

sealed interface AuthError {
    data object Unreachable : AuthError

    data object WrongCredentials : AuthError

    data object PhoneAlreadyRegistered : AuthError

    data object InvalidPhone : AuthError

    data object TooManyAttempts : AuthError

    /** Any other backend message, already written for users. */
    data class Message(val text: String) : AuthError

    data object Unexpected : AuthError
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.SIGN_IN,
    val name: String = "",
    val phone: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val submitting: Boolean = false,
    val error: AuthError? = null,
) {
    /** Every field shown is mandatory. The password has no rules beyond "not empty". */
    val canSubmit: Boolean
        get() =
            !submitting &&
                phone.isNotBlank() &&
                password.isNotEmpty() &&
                (mode == AuthMode.SIGN_IN || name.isNotBlank())
}

const val MAX_NAME_LENGTH = 60
const val MAX_PHONE_LENGTH = 16
const val MAX_PASSWORD_LENGTH = 200

/** Shared by the phone and TV sign-in screens. Success shows up as SessionManager.authState. */
@HiltViewModel
class AuthViewModel
@Inject
constructor(private val repository: AuthRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value.take(MAX_NAME_LENGTH), error = null) }

    fun onPhoneChange(value: String) = _uiState.update {
        it.copy(phone = value.filter { c -> c.isDigit() || c == '+' }.take(MAX_PHONE_LENGTH), error = null)
    }

    fun onPasswordChange(value: String) = _uiState.update {
        it.copy(password = value.take(MAX_PASSWORD_LENGTH), error = null)
    }

    fun onTogglePasswordVisible() = _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun onModeChange(mode: AuthMode) = _uiState.update { it.copy(mode = mode, error = null) }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        _uiState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result =
                when (state.mode) {
                    AuthMode.CREATE_ACCOUNT -> repository.register(state.name.trim(), state.phone, state.password)
                    AuthMode.SIGN_IN -> repository.login(state.phone, state.password)
                }
            _uiState.update {
                when (result) {
                    // The password is not kept in memory once it has done its job.
                    is AppResult.Success -> it.copy(submitting = false, password = "")

                    is AppResult.Failure -> it.copy(submitting = false, error = result.error.toAuthError())
                }
            }
        }
    }

    private fun AppError.toAuthError(): AuthError = when (this) {
        is AppError.Network -> AuthError.Unreachable

        is AppError.Unknown -> AuthError.Unexpected

        is AppError.Api ->
            when (code) {
                "INVALID_CREDENTIALS" -> AuthError.WrongCredentials
                "PHONE_ALREADY_REGISTERED" -> AuthError.PhoneAlreadyRegistered
                "VALIDATION_FAILED" -> AuthError.InvalidPhone
                "RATE_LIMITED" -> AuthError.TooManyAttempts
                "UNKNOWN" -> AuthError.Unexpected
                else -> AuthError.Message(message)
            }
    }
}
