package com.videobridge.feature.auth

import androidx.lifecycle.ViewModel
import com.videobridge.core.common.AppResult
import com.videobridge.core.data.devices.PairingCode
import com.videobridge.core.data.devices.PairingProgress
import com.videobridge.core.data.devices.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

sealed interface TvPairingUiState {
    data object Loading : TvPairingUiState

    data class ShowingCode(
        val code: String,
        val qrPayload: String,
        val secondsLeft: Int,
        /** A phone has entered the code and is looking at "Connect this TV?". */
        val confirmOnPhone: Boolean = false,
        /** The last attempt was refused with "Not my TV" on a phone. */
        val wasRejected: Boolean = false,
    ) : TvPairingUiState

    /** The backend cannot be reached; pairing retries by itself. */
    data object Unreachable : TvPairingUiState
}

/** A fresh code is fetched this long before the old one expires, so one is never shown dead. */
private const val REFRESH_BEFORE_EXPIRY_SECONDS = 10
private const val RETRY_DELAY_MS = 5_000L
private const val MILLIS_PER_SECOND = 1000L

/**
 * Drives the TV's "connect with your phone" screen. [run] loops for as long as the screen is
 * shown (the caller launches it in a scope tied to the screen and cancels it on leaving):
 * get a code, poll until a phone approves, replace the code before it expires.
 * Success is not reported here: the repository signs the app in and the screen goes away.
 */
@HiltViewModel
class TvPairingViewModel
@Inject
constructor(private val repository: PairingRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<TvPairingUiState>(TvPairingUiState.Loading)
    val uiState: StateFlow<TvPairingUiState> = _uiState.asStateFlow()

    suspend fun run() {
        var rejected = false
        while (true) {
            when (val started = repository.start()) {
                is AppResult.Failure -> {
                    _uiState.value = TvPairingUiState.Unreachable
                    delay(RETRY_DELAY_MS)
                }

                is AppResult.Success -> {
                    when (showUntilDone(started.data, rejected)) {
                        PairingProgress.SIGNED_IN -> return
                        PairingProgress.REJECTED -> rejected = true
                        else -> rejected = false
                    }
                }
            }
        }
    }

    /** Shows one code until it is approved, refused, or about to expire. */
    private suspend fun showUntilDone(code: PairingCode, wasRejected: Boolean): PairingProgress {
        // Counted locally from the server's lifetime: TV clocks are often wrong.
        var millisLeft = code.expiresInSeconds * MILLIS_PER_SECOND
        var confirmOnPhone = false
        while (millisLeft > REFRESH_BEFORE_EXPIRY_SECONDS * MILLIS_PER_SECOND) {
            _uiState.value =
                TvPairingUiState.ShowingCode(
                    code = code.code,
                    qrPayload = code.qrPayload,
                    secondsLeft = (millisLeft / MILLIS_PER_SECOND).toInt(),
                    confirmOnPhone = confirmOnPhone,
                    wasRejected = wasRejected && !confirmOnPhone,
                )
            delay(code.pollIntervalMs)
            millisLeft -= code.pollIntervalMs
            when (val polled = repository.poll(code)) {
                // A dropped poll is not an error worth showing: the next one may succeed.
                is AppResult.Failure -> Unit

                is AppResult.Success ->
                    when (polled.data) {
                        PairingProgress.WAITING -> confirmOnPhone = false
                        PairingProgress.CONFIRM_ON_PHONE -> confirmOnPhone = true
                        else -> return polled.data
                    }
            }
        }
        return PairingProgress.ENDED
    }
}
