package com.metrolist.music.extensions

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeSeekTargetTest {
    @Test
    fun `seeks within bounds`() {
        assertEquals(25_000L, relativeSeekTarget(10_000L, 15_000L, 60_000L))
        assertEquals(5_000L, relativeSeekTarget(20_000L, -15_000L, 60_000L))
    }

    @Test
    fun `clamps to start and end`() {
        assertEquals(0L, relativeSeekTarget(5_000L, -15_000L, 60_000L))
        assertEquals(60_000L, relativeSeekTarget(50_000L, 15_000L, 60_000L))
    }

    @Test
    fun `unknown duration only clamps the start`() {
        assertEquals(65_000L, relativeSeekTarget(50_000L, 15_000L, C.TIME_UNSET))
        assertEquals(65_000L, relativeSeekTarget(50_000L, 15_000L, 0L))
    }
}
