package com.videobridge.core.data.devices

import com.videobridge.core.common.AppResult
import com.videobridge.core.common.IoDispatcher
import com.videobridge.core.common.map
import com.videobridge.core.data.apiCall
import com.videobridge.core.data.auth.SessionManager
import com.videobridge.core.data.requireSuccess
import com.videobridge.core.network.ApiErrorParser
import com.videobridge.core.network.DeviceDto
import com.videobridge.core.network.DevicesApi
import com.videobridge.core.network.RenameDeviceRequestDto
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

data class DeviceItem(val id: String, val isTv: Boolean, val name: String, val model: String?, val isThisDevice: Boolean)

interface DevicesRepository {
    suspend fun list(): AppResult<List<DeviceItem>>

    suspend fun rename(id: String, name: String): AppResult<Unit>

    /** Removing this device signs the app out. */
    suspend fun remove(device: DeviceItem): AppResult<Unit>
}

class DefaultDevicesRepository
@Inject
constructor(
    private val api: DevicesApi,
    private val errorParser: ApiErrorParser,
    private val sessionManager: SessionManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DevicesRepository {
    override suspend fun list(): AppResult<List<DeviceItem>> = apiCall(ioDispatcher, errorParser) {
        api.list()
    }.map { it.items.map(DeviceDto::toItem) }

    override suspend fun rename(id: String, name: String): AppResult<Unit> =
        apiCall(ioDispatcher, errorParser) { api.rename(id, RenameDeviceRequestDto(name)) }.map { }

    override suspend fun remove(device: DeviceItem): AppResult<Unit> {
        val result = apiCall(ioDispatcher, errorParser) { api.remove(device.id).requireSuccess() }
        if (result is AppResult.Success && device.isThisDevice) sessionManager.signOutLocally()
        return result
    }
}

private fun DeviceDto.toItem() = DeviceItem(id = id, isTv = type == "ANDROID_TV", name = name, model = model, isThisDevice = current)
