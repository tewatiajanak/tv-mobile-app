package com.videobridge.core.common

/** Outcome of a repository call. Failures are values, not exceptions, once they leave the data layer. */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>

    data class Failure(val error: AppError) : AppResult<Nothing>
}

sealed interface AppError {
    /** The backend answered with its error envelope. [code] is the stable code clients switch on. */
    data class Api(val code: String, val message: String, val httpStatus: Int) : AppError

    /** The backend could not be reached (offline, DNS, timeout, connection refused). */
    data object Network : AppError

    data class Unknown(val cause: Throwable) : AppError
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}
