package com.videobridge.core.data.auth

import com.videobridge.core.common.AppError
import com.videobridge.core.common.AppResult
import com.videobridge.core.common.IoDispatcher
import com.videobridge.core.datastore.StoredSession
import com.videobridge.core.network.ApiErrorParser
import com.videobridge.core.network.AuthApi
import com.videobridge.core.network.AuthResponseDto
import com.videobridge.core.network.LoginRequestDto
import com.videobridge.core.network.RegisterRequestDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

interface AuthRepository {
    suspend fun register(name: String, phone: String, password: String): AppResult<Unit>

    suspend fun login(phone: String, password: String): AppResult<Unit>

    suspend fun logout()
}

class DefaultAuthRepository
@Inject
constructor(
    private val api: AuthApi,
    private val errorParser: ApiErrorParser,
    private val deviceInfo: DeviceInfoProvider,
    private val sessionManager: SessionManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AuthRepository {
    override suspend fun register(name: String, phone: String, password: String): AppResult<Unit> =
        signIn { api.register(RegisterRequestDto(name, phone, password, deviceInfo.get())) }

    override suspend fun login(phone: String, password: String): AppResult<Unit> =
        signIn { api.login(LoginRequestDto(phone, password, deviceInfo.get())) }

    private suspend fun signIn(call: suspend () -> AuthResponseDto): AppResult<Unit> = withContext(ioDispatcher) {
        try {
            val response = call()
            sessionManager.onSignedIn(
                StoredSession(
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken,
                    userId = response.user.id,
                    deviceId = response.device.id,
                    sessionId = response.session.id,
                    displayName = response.user.displayName,
                    phoneMasked = response.user.phoneMasked,
                ),
            )
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val error = errorParser.parse(e)
            AppResult.Failure(AppError.Api(error.code, error.message, error.httpStatus))
        } catch (_: IOException) {
            AppResult.Failure(AppError.Network)
        } catch (e: SerializationException) {
            AppResult.Failure(AppError.Unknown(e))
        }
    }

    /** Signing out always works locally; telling the backend is best effort. */
    override suspend fun logout() {
        withContext(ioDispatcher) {
            try {
                api.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // Offline: the server-side session simply expires on its own.
            } catch (_: HttpException) {
                // Already invalid on the server.
            }
        }
        sessionManager.signOutLocally()
    }
}
