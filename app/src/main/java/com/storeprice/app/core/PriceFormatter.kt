package com.storeprice.app.core

/**
 * Formats a non-negative price in whole tenge.
 *
 * The amount is grouped with non-breaking-free regular spaces so the value is
 * readable on narrow screens and predictable in unit tests.
 */
fun formatKzt(amount: Long): String {
    require(amount >= 0) { "Price must not be negative: $amount" }

    val digits = amount.toString()
    val grouped = digits.reversed()
        .chunked(3)
        .joinToString(" ")
        .reversed()

    return "$grouped \u20B8"
}
