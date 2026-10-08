package com.videobridge.core.data.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.videobridge.core.datastore.InstallIdProvider
import com.videobridge.core.network.DeviceRequestDto
import com.videobridge.core.network.NetworkConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

interface DeviceInfoProvider {
    suspend fun get(): DeviceRequestDto
}

class AndroidDeviceInfoProvider
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val installId: InstallIdProvider,
    private val config: NetworkConfig,
) : DeviceInfoProvider {
    override suspend fun get(): DeviceRequestDto = DeviceRequestDto(
        installId = installId.get(),
        type = if (isTv()) "ANDROID_TV" else "PHONE",
        // The name the user gave the device, when the system exposes it.
        name =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf {
            it.isNotBlank()
        }
            ?: Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        osVersion = Build.VERSION.RELEASE,
        appVersion = config.appVersion,
    )

    private fun isTv(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
