package com.videobridge.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppResultTest {
    @Test
    fun `map transforms a success`() {
        assertEquals(AppResult.Success(4), AppResult.Success(2).map { it * 2 })
    }

    @Test
    fun `map leaves a failure untouched`() {
        val failure: AppResult<Int> = AppResult.Failure(AppError.Network)

        assertSame(failure, failure.map { it * 2 })
    }
}
