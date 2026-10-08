package com.videobridge.core.data

import com.videobridge.core.common.AppError
import com.videobridge.core.common.AppResult
import com.videobridge.core.model.HealthStatus
import com.videobridge.core.network.ApiErrorParser
import com.videobridge.core.network.HealthApi
import com.videobridge.core.network.createJson
import com.videobridge.core.network.createRetrofit
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HealthRepositoryTest {
    private val server = MockWebServer()
    private val json = createJson()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun repository(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        client: OkHttpClient = OkHttpClient(),
    ): HealthRepository {
        val api = createRetrofit(server.url("/").toString(), client, json).create(HealthApi::class.java)
        return DefaultHealthRepository(api, ApiErrorParser(json), StandardTestDispatcher(scheduler))
    }

    @Test
    fun `200 maps to a successful HealthStatus`() = runTest {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("""{"status":"ok","env":"development","version":"0.1.0","time":"2026-10-07T16:26:52.123Z"}""")
                .build(),
        )

        val result = repository(testScheduler).check()

        assertEquals(
            AppResult.Success(HealthStatus("ok", "development", "0.1.0", "2026-10-07T16:26:52.123Z")),
            result,
        )
        assertEquals("/api/v1/health", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `503 envelope maps to an Api failure with the backend code`() = runTest {
        server.enqueue(
            MockResponse
                .Builder()
                .code(503)
                .body(
                    """{"error":{"code":"NOT_READY","message":"The service is not ready.",""" +
                        """"details":{"checks":{"db":"up","redis":"down"}},"requestId":"r1"}}""",
                ).build(),
        )

        val result = repository(testScheduler).check()

        assertEquals(
            AppResult.Failure(AppError.Api("NOT_READY", "The service is not ready.", 503)),
            result,
        )
    }

    @Test
    fun `a non-envelope error body maps to an UNKNOWN Api failure`() = runTest {
        server.enqueue(MockResponse.Builder().code(502).body("<html>Bad Gateway</html>").build())

        val result = repository(testScheduler).check()

        assertEquals("UNKNOWN", ((result as AppResult.Failure).error as AppError.Api).code)
    }

    @Test
    fun `a timeout maps to a Network failure`() = runTest {
        // Nothing is enqueued, so the server never answers and the client's read timeout fires.
        val impatient = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()

        val result = repository(testScheduler, impatient).check()

        assertEquals(AppResult.Failure(AppError.Network), result)
    }

    @Test
    fun `an unreachable server maps to a Network failure`() = runTest {
        val repository = repository(testScheduler)
        server.close()

        assertEquals(AppResult.Failure(AppError.Network), repository.check())
    }

    @Test
    fun `a 200 with an unexpected body maps to an Unknown failure`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"unexpected":true}""").build())

        val result = repository(testScheduler).check()

        assertTrue((result as AppResult.Failure).error is AppError.Unknown)
    }
}
