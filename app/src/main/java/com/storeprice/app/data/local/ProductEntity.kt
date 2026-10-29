package com.storeprice.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["barcode_normalized"]),
        Index(value = ["name_normalized"]),
    ],
)
data class ProductEntity(
    @PrimaryKey val id: String,
    val barcode: String?,
    @ColumnInfo(name = "barcode_normalized") val barcodeNormalized: String?,
    val name: String,
    @ColumnInfo(name = "name_normalized") val nameNormalized: String,
    @ColumnInfo(name = "price_kzt") val priceKzt: Long,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val version: Long,
)
