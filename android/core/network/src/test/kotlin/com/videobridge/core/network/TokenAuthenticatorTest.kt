package com.videobridge.core.network

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TokenAuthenticatorTest {
    private val server = MockWebServer()
    private val json = createJson()
    private val config =
        NetworkConfig("http://unused/", "ws://unused/ws", "0.2.0", "android-phone", logRequests = false)

    private class FakeTokens(@Volatile var access: String? = "old-access", @Volatile var refresh: String? = "old-refresh") :
        AuthTokenSource {
        val ended = AtomicInteger()

        override fun accessToken() = access

        override fun refreshToken() = refresh

        override fun onTokensRefreshed(accessToken: String, refreshToken: String) {
            access = accessToken
            refresh = refreshToken
        }

        override fun onSessionEnded() {
            ended.incrementAndGet()
            access = null
            refresh = null
        }
    }

    private val tokens = FakeTokens()
    private val refreshCalls = AtomicInteger()
    private var refreshResponse: () -> MockResponse = {
        MockResponse.Builder().code(200).body("""{"accessToken":"new-access","refreshToken":"new-refresh"}""").build()
    }

    @Before
    fun setUp() {
        // Only "new-access" is accepted, like a backend after the old token expired.
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.url.encodedPath.endsWith("/auth/refresh") -> {
                        refreshCalls.incrementAndGet()
                        refreshResponse()
                    }

                    request.headers["Authorization"] == "Bearer new-access" -> MockResponse.Builder().code(
                        200,
                    ).body("ok").build()

                    else -> MockResponse.Builder().code(
                        401,
                    ).body("""{"error":{"code":"TOKEN_EXPIRED","message":"x"}}""").build()
                }
            }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun client(): OkHttpClient {
        val base = server.url("/").toString()
        val refreshApi = createRetrofit(base, createOkHttpClient(config), json).create(RefreshApi::class.java)
        return createOkHttpClient(config, tokens to refreshApi)
    }

    private fun get(client: OkHttpClient): Int =
        client.newCall(Request.Builder().url(server.url("/api/v1/users/me")).build()).execute().use { it.code }

    @Test
    fun `an expired token is refreshed once and the request retried`() {
        assertEquals(200, get(client()))

        assertEquals(1, refreshCalls.get())
        assertEquals("new-access", tokens.access)
        assertEquals("new-refresh", tokens.refresh)
        assertEquals(0, tokens.ended.get())
    }

    @Test
    fun `five parallel 401s cause exactly one refresh`() {
        val client = client()
        val pool = Executors.newFixedThreadPool(5)
        val start = CountDownLatch(1)

        val results =
            (1..5).map {
                pool.submit<Int> {
                    start.await()
                    get(client)
                }
            }
        start.countDown()

        assertEquals(List(5) { 200 }, results.map { it.get(10, TimeUnit.SECONDS) })
        assertEquals(1, refreshCalls.get())
        pool.shutdown()
    }

    @Test
    fun `a rejected refresh ends the session without looping`() {
        refreshResponse =
            { MockResponse.Builder().code(401).body("""{"error":{"code":"SESSION_REVOKED","message":"x"}}""").build() }

        assertEquals(401, get(client()))

        assertEquals(1, refreshCalls.get())
        assertEquals(1, tokens.ended.get())
        assertNull(tokens.access)
    }

    @Test
    fun `a server error during refresh keeps the user signed in`() {
        refreshResponse = { MockResponse.Builder().code(503).build() }

        assertEquals(401, get(client()))

        assertEquals(0, tokens.ended.get())
        assertEquals("old-access", tokens.access)
    }

    @Test
    fun `sign-in requests are sent without a token and never trigger a refresh`() {
        val request = Request.Builder().url(server.url("/api/v1/auth/login")).build()

        client().newCall(request).execute().close()

        assertNull(server.takeRequest().headers["Authorization"])
        assertEquals(0, refreshCalls.get())
    }
}
