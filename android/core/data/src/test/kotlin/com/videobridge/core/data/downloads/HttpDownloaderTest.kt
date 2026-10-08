package com.videobridge.core.data.downloads

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class HttpDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val content = (1..5000).joinToString("") { "line $it\n" }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun part() = tmp.newFile("video.mp4.part").also { it.delete() }

    private fun url() = server.url("/v.mp4").toString()

    @Test
    fun `downloads a whole file and reports its size`() {
        server.enqueue(MockResponse.Builder().code(200).body(content).build())
        val part = part()
        var total = -1L

        val outcome = downloadInto(client, url(), part, isStopped = { false }) { total = it }

        assertEquals(DownloadOutcome.Completed, outcome)
        assertEquals(content, part.readText())
        assertEquals(content.length.toLong(), total)
        assertNull(server.takeRequest().headers["Range"])
    }

    @Test
    fun `resume asks only for the missing bytes and appends them - the result is byte-identical`() {
        val part = part()
        part.writeText(content.take(12_345))
        server.enqueue(MockResponse.Builder().code(206).body(content.drop(12_345)).build())
        var total = -1L

        val outcome = downloadInto(client, url(), part, isStopped = { false }) { total = it }

        assertEquals(DownloadOutcome.Completed, outcome)
        assertEquals("bytes=12345-", server.takeRequest().headers["Range"])
        assertEquals(content, part.readText())
        assertEquals(content.length.toLong(), total)
    }

    @Test
    fun `a server that ignores the range restarts from zero instead of corrupting the file`() {
        val part = part()
        part.writeText("stale partial data")
        server.enqueue(MockResponse.Builder().code(200).body(content).build())

        downloadInto(client, url(), part, isStopped = { false })

        assertEquals(content, part.readText())
    }

    @Test
    fun `stopping keeps what was fetched so far`() {
        server.enqueue(MockResponse.Builder().code(200).body(content).build())
        val part = part()
        var checks = 0

        val outcome = downloadInto(client, url(), part, isStopped = { checks++ >= 1 })

        assertEquals(DownloadOutcome.Stopped, outcome)
        assertEquals(content.take(part.length().toInt()), part.readText())
    }

    @Test
    fun `everything already on disk counts as complete`() {
        val part = part()
        part.writeText(content)
        server.enqueue(MockResponse.Builder().code(416).build())

        assertEquals(DownloadOutcome.Completed, downloadInto(client, url(), part, isStopped = { false }))
        assertEquals(content, part.readText())
    }

    @Test
    fun `an expired or missing link is refused, not retried forever`() {
        server.enqueue(MockResponse.Builder().code(403).build())
        server.enqueue(MockResponse.Builder().code(404).build())

        assertEquals(DownloadOutcome.Refused(403), downloadInto(client, url(), part(), isStopped = { false }))
        assertEquals(DownloadOutcome.Refused(404), downloadInto(client, url(), tmp.newFile("b.part"), isStopped = { false }))
    }

    @Test
    fun `an unreachable server is a network error, so the caller retries later`() {
        val url = url()
        server.close()

        assertThrows(IOException::class.java) { downloadInto(client, url, part(), isStopped = { false }) }
    }
}
