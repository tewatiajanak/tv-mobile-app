package com.videobridge.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class HttpClientFactoryTest {
    private val server = MockWebServer()
    private val config =
        NetworkConfig(
            apiBaseUrl = "http://unused/",
            wsUrl = "ws://unused/ws",
            appVersion = "0.1.0",
            platform = "android-tv",
            logRequests = false,
        )

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun `adds app version, platform and a request id to every request`() {
        server.enqueue(MockResponse.Builder().code(200).build())

        createOkHttpClient(config).newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        val recorded = server.takeRequest()
        assertEquals("0.1.0", recorded.headers["X-App-Version"])
        assertEquals("android-tv", recorded.headers["X-Platform"])
        assertNotNull(recorded.headers["X-Request-Id"])
    }

    @Test
    fun `keeps a request id set by the caller`() {
        server.enqueue(MockResponse.Builder().code(200).build())
        val request = Request.Builder().url(server.url("/x")).header("X-Request-Id", "mine").build()

        createOkHttpClient(config).newCall(request).execute().close()

        assertEquals("mine", server.takeRequest().headers["X-Request-Id"])
    }

    @Test
    fun `query strings never reach the log`() {
        assertEquals(
            "--> GET http://10.0.2.2:3000/api/v1/videos",
            stripQueryStrings("--> GET http://10.0.2.2:3000/api/v1/videos?token=secret&x=1"),
        )
        assertEquals(
            "<-- 200 OK http://h/a (12ms, 83-byte body)",
            stripQueryStrings("<-- 200 OK http://h/a?sig=abc (12ms, 83-byte body)"),
        )
    }
}
