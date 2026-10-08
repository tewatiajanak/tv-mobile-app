package com.videobridge.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videobridge.core.common.AppError
import com.videobridge.core.common.AppResult
import com.videobridge.core.data.devices.ClaimedTv
import com.videobridge.core.data.devices.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConnectTvError {
    data object Unreachable : ConnectTvError

    data object InvalidCode : ConnectTvError

    data object AlreadyClaimed : ConnectTvError

    data object Expired : ConnectTvError

    data object DeviceLimit : ConnectTvError

    data object TooManyAttempts : ConnectTvError

    data class Message(val text: String) : ConnectTvError
}

data class ConnectTvUiState(
    /** The digits typed so far. */
    val code: String = "",
    val busy: Boolean = false,
    val error: ConnectTvError? = null,
    /** Set once the code is accepted: the phone now asks "Connect this TV?". */
    val tv: ClaimedTv? = null,
    val tvName: String = "",
    val connected: Boolean = false,
) {
    val canSubmitCode: Boolean get() = !busy && code.length == CODE_LENGTH
}

const val CODE_LENGTH = 4

/** Phone side of pairing: enter or scan the TV's code, then confirm. */
@HiltViewModel
class ConnectTvViewModel
@Inject
constructor(private val repository: PairingRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(ConnectTvUiState())
    val uiState: StateFlow<ConnectTvUiState> = _uiState.asStateFlow()

    fun onCodeChange(value: String) {
        _uiState.update { it.copy(code = value.filter(Char::isDigit).take(CODE_LENGTH), error = null) }
    }

    /** A scanned QR holds `videobridge://pair?c=1234`; a bare code works too. */
    fun onScanned(payload: String) {
        onCodeChange(payload.substringAfter("c=", payload).substringBefore('&'))
        submitCode()
    }

    fun submitCode() {
        val state = _uiState.value
        if (!state.canSubmitCode) return
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = repository.claim(state.code)
            _uiState.update {
                when (result) {
                    is AppResult.Success -> it.copy(busy = false, tv = result.data, tvName = result.data.name)
                    is AppResult.Failure -> it.copy(busy = false, error = result.error.toConnectError())
                }
            }
        }
    }

    fun onTvNameChange(value: String) = _uiState.update { it.copy(tvName = value.take(MAX_NAME_LENGTH)) }

    fun approve() {
        val state = _uiState.value
        val tv = state.tv ?: return
        if (state.busy) return
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = repository.approve(tv.pairingId, state.tvName.trim())
            _uiState.update {
                when (result) {
                    is AppResult.Success -> it.copy(busy = false, connected = true)
                    is AppResult.Failure -> it.copy(busy = false, error = result.error.toConnectError())
                }
            }
        }
    }

    /** "Not my TV": tells the TV, then returns to code entry. */
    fun reject() {
        val tv = _uiState.value.tv ?: return
        _uiState.value = ConnectTvUiState()
        viewModelScope.launch { repository.reject(tv.pairingId) }
    }

    fun reset() {
        _uiState.value = ConnectTvUiState()
    }

    private fun AppError.toConnectError(): ConnectTvError = when (this) {
        is AppError.Network -> ConnectTvError.Unreachable

        is AppError.Unknown -> ConnectTvError.Message("")

        is AppError.Api ->
            when (code) {
                "PAIRING_CODE_INVALID", "VALIDATION_FAILED" -> ConnectTvError.InvalidCode
                "PAIRING_ALREADY_CLAIMED" -> ConnectTvError.AlreadyClaimed
                "PAIRING_EXPIRED", "NOT_FOUND" -> ConnectTvError.Expired
                "ENTITLEMENT_LIMIT" -> ConnectTvError.DeviceLimit
                "RATE_LIMITED" -> ConnectTvError.TooManyAttempts
                else -> ConnectTvError.Message(message)
            }
    }
}
