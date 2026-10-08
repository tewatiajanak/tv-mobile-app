package com.videobridge.core.data.devices

import com.videobridge.core.common.AppResult
import com.videobridge.core.common.IoDispatcher
import com.videobridge.core.common.map
import com.videobridge.core.data.apiCall
import com.videobridge.core.data.auth.DeviceInfoProvider
import com.videobridge.core.data.auth.SessionManager
import com.videobridge.core.data.requireSuccess
import com.videobridge.core.datastore.StoredSession
import com.videobridge.core.network.ApiErrorParser
import com.videobridge.core.network.ApproveRequestDto
import com.videobridge.core.network.ClaimRequestDto
import com.videobridge.core.network.CreatePairingRequestDto
import com.videobridge.core.network.PairingApi
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

/** What a signed-out TV shows while it waits to be connected. */
data class PairingCode(
    val pairingId: String,
    /** Four digits, e.g. "0427". */
    val code: String,
    val qrPayload: String,
    /** Secret: proves this TV owns the session. Never shown or logged. */
    val pollToken: String,
    val expiresInSeconds: Int,
    val pollIntervalMs: Long,
)

enum class PairingProgress { WAITING, CONFIRM_ON_PHONE, SIGNED_IN, REJECTED, ENDED }

data class ClaimedTv(val pairingId: String, val name: String, val model: String?)

interface PairingRepository {
    /** TV: ask the backend for a new code. */
    suspend fun start(): AppResult<PairingCode>

    /** TV: check progress. On SIGNED_IN the session is already saved and the app is signed in. */
    suspend fun poll(code: PairingCode): AppResult<PairingProgress>

    /** Phone: the code typed or scanned from the TV. */
    suspend fun claim(code: String): AppResult<ClaimedTv>

    suspend fun approve(pairingId: String, name: String?): AppResult<Unit>

    suspend fun reject(pairingId: String): AppResult<Unit>
}

class DefaultPairingRepository
@Inject
constructor(
    private val api: PairingApi,
    private val errorParser: ApiErrorParser,
    private val deviceInfo: DeviceInfoProvider,
    private val sessionManager: SessionManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PairingRepository {
    override suspend fun start(): AppResult<PairingCode> =
        apiCall(ioDispatcher, errorParser) { api.create(CreatePairingRequestDto(deviceInfo.get())) }.map {
            PairingCode(it.pairingId, it.code, it.qrPayload, it.pollToken, it.expiresInSeconds, it.pollIntervalMs)
        }

    override suspend fun poll(code: PairingCode): AppResult<PairingProgress> = apiCall(ioDispatcher, errorParser) {
        val response = api.status(code.pairingId, code.pollToken)
        val auth = response.auth
        when {
            response.status == "APPROVED" && auth != null -> {
                sessionManager.onSignedIn(
                    StoredSession(
                        accessToken = auth.accessToken,
                        refreshToken = auth.refreshToken,
                        userId = auth.user.id,
                        deviceId = auth.device.id,
                        sessionId = auth.session.id,
                        displayName = auth.user.displayName,
                        phoneMasked = auth.user.phoneMasked,
                    ),
                )
                PairingProgress.SIGNED_IN
            }

            response.status == "PENDING" -> PairingProgress.WAITING

            response.status == "CLAIMED" -> PairingProgress.CONFIRM_ON_PHONE

            response.status == "REJECTED" -> PairingProgress.REJECTED

            // EXPIRED, CANCELLED, CONSUMED, or anything a newer backend adds.
            else -> PairingProgress.ENDED
        }
    }

    override suspend fun claim(code: String): AppResult<ClaimedTv> =
        apiCall(ioDispatcher, errorParser) { api.claim(ClaimRequestDto(code)) }.map { ClaimedTv(it.pairingId, it.tv.name, it.tv.model) }

    override suspend fun approve(pairingId: String, name: String?): AppResult<Unit> = apiCall(ioDispatcher, errorParser) {
        api.approve(pairingId, ApproveRequestDto(name?.takeIf { it.isNotBlank() }))
    }.map { }

    override suspend fun reject(pairingId: String): AppResult<Unit> = apiCall(ioDispatcher, errorParser) {
        api.reject(pairingId).requireSuccess()
    }
}
