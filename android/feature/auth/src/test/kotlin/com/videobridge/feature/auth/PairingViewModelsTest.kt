package com.videobridge.feature.auth

import com.videobridge.core.common.AppError
import com.videobridge.core.common.AppResult
import com.videobridge.core.data.devices.ClaimedTv
import com.videobridge.core.data.devices.PairingCode
import com.videobridge.core.data.devices.PairingProgress
import com.videobridge.core.data.devices.PairingRepository
import com.videobridge.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakePairingRepository : PairingRepository {
    var starts = 0
    var startResult: () -> AppResult<PairingCode> = {
        AppResult.Success(PairingCode("p$starts", "000$starts", "videobridge://pair?c=000$starts", "secret", 300, 2000))
    }
    var progress: PairingProgress = PairingProgress.WAITING
    var pollFails = false
    var claimResult: AppResult<ClaimedTv> = AppResult.Success(ClaimedTv("p1", "Living Room TV", "MiTV"))
    var approveResult: AppResult<Unit> = AppResult.Success(Unit)
    val calls = mutableListOf<String>()

    override suspend fun start(): AppResult<PairingCode> {
        starts++
        return startResult()
    }

    override suspend fun poll(code: PairingCode): AppResult<PairingProgress> =
        if (pollFails) AppResult.Failure(AppError.Network) else AppResult.Success(progress)

    override suspend fun claim(code: String): AppResult<ClaimedTv> {
        calls += "claim|$code"
        return claimResult
    }

    override suspend fun approve(pairingId: String, name: String?): AppResult<Unit> {
        calls += "approve|$pairingId|$name"
        return approveResult
    }

    override suspend fun reject(pairingId: String): AppResult<Unit> {
        calls += "reject|$pairingId"
        return AppResult.Success(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TvPairingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakePairingRepository()
    private val viewModel = TvPairingViewModel(repository)

    private fun showing() = viewModel.uiState.value as TvPairingUiState.ShowingCode

    @Test
    fun `shows a code, counts down, and reflects a phone entering it`() = runTest {
        val job = launch { viewModel.run() }
        runCurrent()
        assertEquals("0001", showing().code)
        assertEquals(300, showing().secondsLeft)
        assertFalse(showing().confirmOnPhone)

        repository.progress = PairingProgress.CONFIRM_ON_PHONE
        advanceTimeBy(2_001)
        assertTrue(showing().confirmOnPhone)
        assertEquals(298, showing().secondsLeft)
        job.cancel()
    }

    @Test
    fun `stops once the phone approves`() = runTest {
        val job = launch { viewModel.run() }
        runCurrent()
        repository.progress = PairingProgress.SIGNED_IN
        advanceTimeBy(2_001)

        assertTrue(job.isCompleted)
        assertEquals(1, repository.starts)
    }

    @Test
    fun `gets a new code when refused, and says so`() = runTest {
        val job = launch { viewModel.run() }
        runCurrent()
        repository.progress = PairingProgress.REJECTED
        advanceTimeBy(2_001)
        repository.progress = PairingProgress.WAITING
        runCurrent()

        assertEquals("0002", showing().code)
        assertTrue(showing().wasRejected)
        job.cancel()
    }

    @Test
    fun `replaces the code shortly before it expires, and survives failed polls`() = runTest {
        repository.pollFails = true
        val job = launch { viewModel.run() }
        runCurrent()

        advanceTimeBy(289_000)
        assertEquals("0001", showing().code)
        advanceTimeBy(3_000)
        assertEquals("0002", showing().code)
        job.cancel()
    }

    @Test
    fun `retries by itself while the backend is unreachable`() = runTest {
        val ok = repository.startResult
        repository.startResult = { AppResult.Failure(AppError.Network) }
        val job = launch { viewModel.run() }
        runCurrent()
        assertEquals(TvPairingUiState.Unreachable, viewModel.uiState.value)

        repository.startResult = ok
        advanceTimeBy(5_001)
        assertTrue(viewModel.uiState.value is TvPairingUiState.ShowingCode)
        job.cancel()
    }
}

class ConnectTvViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakePairingRepository()
    private val viewModel = ConnectTvViewModel(repository)

    @Test
    fun `keeps only four digits and only submits a complete code`() {
        viewModel.onCodeChange("12")
        assertEquals("12", viewModel.uiState.value.code)
        assertFalse(viewModel.uiState.value.canSubmitCode)
        viewModel.submitCode()
        assertTrue(repository.calls.isEmpty())

        viewModel.onCodeChange("12 a3-4 567")
        assertEquals("1234", viewModel.uiState.value.code)
        assertTrue(viewModel.uiState.value.canSubmitCode)
    }

    @Test
    fun `code, confirm with a new name, connected`() {
        viewModel.onCodeChange("1234")
        viewModel.submitCode()
        assertEquals("Living Room TV", viewModel.uiState.value.tvName)
        assertEquals("MiTV", viewModel.uiState.value.tv?.model)

        viewModel.onTvNameChange(" Bedroom TV ")
        viewModel.approve()

        assertTrue(viewModel.uiState.value.connected)
        assertEquals(listOf("claim|1234", "approve|p1|Bedroom TV"), repository.calls)
    }

    @Test
    fun `a scanned QR code is claimed straight away`() {
        viewModel.onScanned("videobridge://pair?c=6789")

        assertEquals(listOf("claim|6789"), repository.calls)
        assertEquals("Living Room TV", viewModel.uiState.value.tv?.name)
    }

    @Test
    fun `Not my TV tells the backend and returns to code entry`() {
        viewModel.onCodeChange("1234")
        viewModel.submitCode()

        viewModel.reject()

        assertNull(viewModel.uiState.value.tv)
        assertEquals("", viewModel.uiState.value.code)
        assertEquals("reject|p1", repository.calls.last())
    }

    @Test
    fun `each failure has its own message`() {
        val cases =
            listOf(
                AppError.Network to ConnectTvError.Unreachable,
                AppError.Api("PAIRING_CODE_INVALID", "x", 404) to ConnectTvError.InvalidCode,
                AppError.Api("PAIRING_ALREADY_CLAIMED", "x", 409) to ConnectTvError.AlreadyClaimed,
                AppError.Api("RATE_LIMITED", "x", 429) to ConnectTvError.TooManyAttempts,
            )
        cases.forEach { (failure, expected) ->
            repository.claimResult = AppResult.Failure(failure)
            viewModel.onCodeChange("1234")
            viewModel.submitCode()
            assertEquals(expected, viewModel.uiState.value.error)
        }

        repository.claimResult = AppResult.Success(ClaimedTv("p1", "TV", null))
        repository.approveResult = AppResult.Failure(AppError.Api("ENTITLEMENT_LIMIT", "x", 403))
        viewModel.onCodeChange("1234")
        viewModel.submitCode()
        viewModel.approve()
        assertEquals(ConnectTvError.DeviceLimit, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.connected)
    }
}
