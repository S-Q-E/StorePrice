package com.storeprice.app.domain.model

/**
 * A catalog product as shown to the UI.
 *
 * Deleted products are filtered out by the repository and never reach this model.
 */
data class Product(
    val id: String,
    val barcode: String?,
    val name: String,
    val priceKzt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Long,
)
