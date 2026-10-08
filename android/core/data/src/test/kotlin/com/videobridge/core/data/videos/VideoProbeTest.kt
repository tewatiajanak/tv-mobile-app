package com.videobridge.core.data.videos

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class VideoProbeTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun `reads size and format from a HEAD request, sending no account headers`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(
                200,
            ).addHeader("Content-Length", "734003200").addHeader("Content-Type", "video/x-matroska").build(),
        )

        val info = VideoProbe(StandardTestDispatcher(testScheduler)).inspect(server.url("/a/movie").toString())

        assertEquals(VideoInfo(734_003_200, "MKV"), info)
        val request = server.takeRequest()
        assertEquals("HEAD", request.method)
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["X-Platform"])
    }

    @Test
    fun `falls back to a one-byte range request when HEAD is refused`() = runTest {
        server.enqueue(MockResponse.Builder().code(405).build())
        server.enqueue(
            MockResponse.Builder().code(
                206,
            ).addHeader("Content-Range", "bytes 0-0/5368709120").addHeader("Content-Type", "video/mp4").body("x").build(),
        )

        val info = VideoProbe(StandardTestDispatcher(testScheduler)).inspect(server.url("/v.bin").toString())

        assertEquals(VideoInfo(5_368_709_120, "MP4"), info)
        server.takeRequest()
        assertEquals("bytes=0-0", server.takeRequest().headers["Range"])
    }

    @Test
    fun `an unreachable or unhelpful server still yields the format from the link`() = runTest {
        val probe = VideoProbe(StandardTestDispatcher(testScheduler))
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(VideoInfo(null, "HLS"), probe.inspect(server.url("/live/index.m3u8?token=1").toString()))

        val url = server.url("/x/clip.mp4").toString()
        server.close()
        assertEquals(VideoInfo(null, "MP4"), probe.inspect(url))
        assertEquals(VideoInfo(), probe.inspect("not a url"))
    }

    @Test
    fun `format comes from the content type first, then the extension`() {
        assertEquals("HLS", VideoProbe.formatFromContentType("application/vnd.apple.mpegurl; charset=utf-8"))
        assertNull(VideoProbe.formatFromContentType("text/html"))
        assertEquals("WEBM", VideoProbe.formatFromUrl("https://x.example/a/b.WEBM#t=1"))
        assertNull(VideoProbe.formatFromUrl("https://x.example/watch?v=1"))
        assertNull(VideoProbe.formatFromUrl("https://x.example/file.verylongext"))
    }

    @Test
    fun `a web page link gets the picture the page advertises, and nothing else is trusted`() = runTest {
        val probe = VideoProbe(StandardTestDispatcher(testScheduler))
        val html = """<html><head><meta property="og:image" content="https://img.example/p.jpg?a=1&amp;b=2"></head></html>"""
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=utf-8").build())
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=utf-8").body(html).build())

        assertEquals("https://img.example/p.jpg?a=1&b=2", probe.inspect(server.url("/watch/1").toString()).thumbnailUrl)

        val hostile = """<meta content="javascript:alert(1)" property="og:image">"""
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").build())
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body(hostile).build())
        assertNull(probe.inspect(server.url("/watch/2").toString()).thumbnailUrl)
    }

    @Test
    fun `a video file is never fetched for a picture`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Length", "10").addHeader("Content-Type", "video/mp4").build(),
        )

        val info = VideoProbe(StandardTestDispatcher(testScheduler)).inspect(server.url("/a.mp4").toString())

        assertNull(info.thumbnailUrl)
        assertEquals(1, server.requestCount)
    }
}
