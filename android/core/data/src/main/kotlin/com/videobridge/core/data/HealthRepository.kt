package com.videobridge.core.data

import com.videobridge.core.common.AppError
import com.videobridge.core.common.AppResult
import com.videobridge.core.common.IoDispatcher
import com.videobridge.core.model.HealthStatus
import com.videobridge.core.network.ApiErrorParser
import com.videobridge.core.network.HealthApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

interface HealthRepository {
    suspend fun check(): AppResult<HealthStatus>
}

class DefaultHealthRepository
@Inject
constructor(
    private val api: HealthApi,
    private val errorParser: ApiErrorParser,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : HealthRepository {
    override suspend fun check(): AppResult<HealthStatus> = withContext(ioDispatcher) {
        try {
            val dto = api.health()
            AppResult.Success(HealthStatus(dto.status, dto.env, dto.version, dto.time))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            val api = errorParser.parse(e)
            AppResult.Failure(AppError.Api(api.code, api.message, api.httpStatus))
        } catch (_: IOException) {
            AppResult.Failure(AppError.Network)
        } catch (e: SerializationException) {
            AppResult.Failure(AppError.Unknown(e))
        }
    }
}
