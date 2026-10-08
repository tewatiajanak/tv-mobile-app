package com.videobridge.core.data

import com.videobridge.core.common.AppError
import com.videobridge.core.common.AppResult
import com.videobridge.core.network.ApiErrorParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** Runs a backend call and turns every way it can fail into an [AppResult.Failure]. */
internal suspend fun <T> apiCall(dispatcher: CoroutineDispatcher, errorParser: ApiErrorParser, block: suspend () -> T): AppResult<T> =
    withContext(dispatcher) {
        try {
            AppResult.Success(block())
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

/** For endpoints that answer 204: a non-2xx response becomes an HttpException like any other. */
internal fun Response<Unit>.requireSuccess() {
    if (!isSuccessful) throw HttpException(this)
}
