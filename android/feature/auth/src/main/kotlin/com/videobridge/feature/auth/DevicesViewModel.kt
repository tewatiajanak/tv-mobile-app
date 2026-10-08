package com.videobridge.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videobridge.core.common.AppResult
import com.videobridge.core.data.devices.DeviceItem
import com.videobridge.core.data.devices.DevicesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DevicesUiState(
    val loading: Boolean = true,
    val devices: List<DeviceItem> = emptyList(),
    /** Loading or changing the list failed; the list shown may be stale. */
    val failed: Boolean = false,
)

@HiltViewModel
class DevicesViewModel
@Inject
constructor(private val repository: DevicesRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            val result = repository.list()
            _uiState.update {
                when (result) {
                    is AppResult.Success -> DevicesUiState(loading = false, devices = result.data)
                    is AppResult.Failure -> it.copy(loading = false, failed = true)
                }
            }
        }
    }

    fun rename(device: DeviceItem, name: String) = change { repository.rename(device.id, name.trim()) }

    fun remove(device: DeviceItem) = change { repository.remove(device) }

    private fun change(action: suspend () -> AppResult<Unit>) {
        viewModelScope.launch {
            if (action() is AppResult.Failure) _uiState.update { it.copy(failed = true) } else refresh()
        }
    }
}
