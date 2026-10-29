package com.storeprice.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PriceFormatterTest {

    @Test
    fun `zero is formatted with currency suffix`() {
        assertEquals("0 \u20B8", formatKzt(0))
    }

    @Test
    fun `thousands are grouped with spaces`() {
        assertEquals("1 234 \u20B8", formatKzt(1_234))
    }

    @Test
    fun `large amounts are grouped in triples`() {
        assertEquals("1 234 567 \u20B8", formatKzt(1_234_567))
    }

    @Test
    fun `negative amount is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            formatKzt(-1)
        }
    }
}
