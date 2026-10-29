package com.storeprice.app.core

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class DateTimeFormatTest {

    @Test
    fun `timestamp is formatted in the requested zone`() {
        assertEquals("01.01.1970 00:00", formatTimestamp(0L, ZoneOffset.UTC))
    }
}
