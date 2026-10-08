package com.videobridge.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiErrorParserTest {
    private val parser = ApiErrorParser(createJson())

    @Test
    fun `decodes a valid envelope`() {
        val body =
            """
            {"error":{"code":"OTP_EXPIRED","message":"The code has expired. Request a new one.",
            "details":{"retryAfterSeconds":30},"requestId":"01J9"}}
            """.trimIndent()

        val exception = parser.parse(400, body)

        assertEquals("OTP_EXPIRED", exception.code)
        assertEquals("The code has expired. Request a new one.", exception.message)
        assertEquals(400, exception.httpStatus)
        assertEquals("30", exception.details?.get("retryAfterSeconds").toString())
    }

    @Test
    fun `decodes an envelope without details and ignores unknown fields`() {
        val exception = parser.parse(404, """{"error":{"code":"NOT_FOUND","message":"Not found.","future":true}}""")

        assertEquals("NOT_FOUND", exception.code)
        assertNull(exception.details)
    }

    @Test
    fun `falls back to UNKNOWN for a malformed body`() {
        listOf("<html>502 Bad Gateway</html>", """{"error":""", """{"message":"no envelope"}""", "[]").forEach { body ->
            val exception = parser.parse(502, body)

            assertEquals(body, ApiException.CODE_UNKNOWN, exception.code)
            assertEquals(502, exception.httpStatus)
        }
    }

    @Test
    fun `falls back to UNKNOWN for an empty or missing body`() {
        listOf(null, "", "   ").forEach { body ->
            val exception = parser.parse(503, body)

            assertEquals(ApiException.CODE_UNKNOWN, exception.code)
            assertEquals("Unexpected response from the server (503).", exception.message)
        }
    }
}
