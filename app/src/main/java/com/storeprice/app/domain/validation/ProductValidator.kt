package com.storeprice.app.domain.validation

import com.storeprice.app.core.SearchText

data class ProductDraft(
    val name: String,
    val priceKzt: Long,
    val barcode: String?,
)

enum class ProductFieldError {
    NAME_REQUIRED,
    PRICE_REQUIRED,
    PRICE_INVALID,
    PRICE_TOO_LARGE,
    BARCODE_TOO_LONG,
    BARCODE_DUPLICATE,
}

sealed interface ProductValidationResult {
    data class Valid(val draft: ProductDraft) : ProductValidationResult

    data class Invalid(
        val nameError: ProductFieldError? = null,
        val priceError: ProductFieldError? = null,
        val barcodeError: ProductFieldError? = null,
    ) : ProductValidationResult
}

/**
 * Validates and normalizes user input before it reaches the repository.
 *
 * Prices are whole tenge, so only non-negative integer input is accepted.
 */
object ProductValidator {

    const val MAX_BARCODE_LENGTH = 64
    const val MAX_PRICE_KZT = 100_000_000L

    fun validate(
        nameInput: String,
        priceInput: String,
        barcodeInput: String,
    ): ProductValidationResult {
        val name = SearchText.normalizeDisplay(nameInput)
        val nameError = if (name.isBlank()) ProductFieldError.NAME_REQUIRED else null

        val priceDigits = priceInput.filterNot { it.isWhitespace() || it == '\u00A0' }
        val price = priceDigits.toLongOrNull()
        val priceError = when {
            priceDigits.isEmpty() -> ProductFieldError.PRICE_REQUIRED
            !priceDigits.all { it in '0'..'9' } -> ProductFieldError.PRICE_INVALID
            price == null || price > MAX_PRICE_KZT -> ProductFieldError.PRICE_TOO_LARGE
            else -> null
        }

        val barcode = barcodeInput.trim().takeIf { it.isNotEmpty() }
        val barcodeError = if (barcode != null && barcode.length > MAX_BARCODE_LENGTH) {
            ProductFieldError.BARCODE_TOO_LONG
        } else {
            null
        }

        if (nameError != null || priceError != null || barcodeError != null) {
            return ProductValidationResult.Invalid(
                nameError = nameError,
                priceError = priceError,
                barcodeError = barcodeError,
            )
        }

        return ProductValidationResult.Valid(
            ProductDraft(
                name = name,
                priceKzt = requireNotNull(price),
                barcode = barcode,
            ),
        )
    }
}
