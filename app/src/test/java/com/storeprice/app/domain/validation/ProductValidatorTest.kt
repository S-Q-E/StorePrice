package com.storeprice.app.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductValidatorTest {

    @Test
    fun `valid input is trimmed and normalized`() {
        val result = ProductValidator.validate(
            nameInput = "  Молоко   3,2% ",
            priceInput = "12 345",
            barcodeInput = " 00123 ",
        ) as ProductValidationResult.Valid

        assertEquals("Молоко 3,2%", result.draft.name)
        assertEquals(12_345L, result.draft.priceKzt)
        assertEquals("00123", result.draft.barcode)
    }

    @Test
    fun `blank name is rejected`() {
        val result = ProductValidator.validate("   ", "100", "") as ProductValidationResult.Invalid
        assertEquals(ProductFieldError.NAME_REQUIRED, result.nameError)
    }

    @Test
    fun `empty price is rejected`() {
        val result = ProductValidator.validate("Молоко", "  ", "") as ProductValidationResult.Invalid
        assertEquals(ProductFieldError.PRICE_REQUIRED, result.priceError)
    }

    @Test
    fun `negative price is rejected`() {
        val result = ProductValidator.validate("Молоко", "-10", "") as ProductValidationResult.Invalid
        assertEquals(ProductFieldError.PRICE_INVALID, result.priceError)
    }

    @Test
    fun `decimal price is rejected`() {
        val result = ProductValidator.validate("Молоко", "12.50", "") as ProductValidationResult.Invalid
        assertEquals(ProductFieldError.PRICE_INVALID, result.priceError)
    }

    @Test
    fun `too large price is rejected`() {
        val result = ProductValidator.validate(
            "Молоко",
            "99999999999999999999",
            "",
        ) as ProductValidationResult.Invalid
        assertEquals(ProductFieldError.PRICE_TOO_LARGE, result.priceError)
    }

    @Test
    fun `zero price is allowed`() {
        val result = ProductValidator.validate("Акция", "0", "") as ProductValidationResult.Valid
        assertEquals(0L, result.draft.priceKzt)
    }

    @Test
    fun `blank barcode becomes null`() {
        val result = ProductValidator.validate("Молоко", "100", "   ") as ProductValidationResult.Valid
        assertNull(result.draft.barcode)
    }

    @Test
    fun `leading zeros in barcode are preserved`() {
        val result = ProductValidator.validate("Молоко", "100", "0000123") as ProductValidationResult.Valid
        assertEquals("0000123", result.draft.barcode)
    }

    @Test
    fun `too long barcode is rejected`() {
        val result = ProductValidator.validate(
            "Молоко",
            "100",
            "1".repeat(ProductValidator.MAX_BARCODE_LENGTH + 1),
        ) as ProductValidationResult.Invalid
        assertEquals(ProductFieldError.BARCODE_TOO_LONG, result.barcodeError)
    }

    @Test
    fun `all errors can be reported together`() {
        val result = ProductValidator.validate("", "", "1".repeat(100)) as ProductValidationResult.Invalid
        assertTrue(result.nameError != null)
        assertTrue(result.priceError != null)
        assertTrue(result.barcodeError != null)
    }
}
