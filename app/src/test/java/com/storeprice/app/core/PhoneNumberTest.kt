package com.storeprice.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberTest {

    @Test
    fun `kazakh number with eight is converted to e164`() {
        assertEquals("+77001234567", PhoneNumber.normalize("8 (700) 123-45-67"))
    }

    @Test
    fun `number with country code is preserved`() {
        assertEquals("+77001234567", PhoneNumber.normalize("+7 700 123 45 67"))
        assertEquals("+12025550143", PhoneNumber.normalize("+1 (202) 555-0143"))
    }

    @Test
    fun `ten digit local number gets kazakhstan code`() {
        assertEquals("+77001234567", PhoneNumber.normalize("7001234567"))
    }

    @Test
    fun `eleven digits starting with seven are accepted`() {
        assertEquals("+77001234567", PhoneNumber.normalize("77001234567"))
    }

    @Test
    fun `empty short and non numeric values are rejected`() {
        assertNull(PhoneNumber.normalize(""))
        assertNull(PhoneNumber.normalize("   "))
        assertNull(PhoneNumber.normalize("123"))
        assertNull(PhoneNumber.normalize("+7 (700) abc"))
        assertNull(PhoneNumber.normalize("1234567890123456"))
    }

    @Test
    fun `arabic digits are not treated as phone digits`() {
        assertNull(PhoneNumber.normalize("٧٧٠٠١٢٣٤٥٦٧"))
    }
}
