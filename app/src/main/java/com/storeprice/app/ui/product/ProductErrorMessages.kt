package com.storeprice.app.ui.product

import androidx.annotation.StringRes
import com.storeprice.app.R
import com.storeprice.app.domain.validation.ProductFieldError

@StringRes
fun ProductFieldError.messageRes(): Int = when (this) {
    ProductFieldError.NAME_REQUIRED -> R.string.error_name_required
    ProductFieldError.PRICE_REQUIRED -> R.string.error_price_required
    ProductFieldError.PRICE_INVALID -> R.string.error_price_invalid
    ProductFieldError.PRICE_TOO_LARGE -> R.string.error_price_too_large
    ProductFieldError.BARCODE_TOO_LONG -> R.string.error_barcode_too_long
    ProductFieldError.BARCODE_DUPLICATE -> R.string.error_barcode_duplicate
}
