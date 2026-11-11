package com.storeprice.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {

    @Query("SELECT * FROM products WHERE is_deleted = 0 ORDER BY id ASC")
    fun observeAll(): Flow<List<ProductEntity>>

    @Query(
        """
        SELECT * FROM products
        WHERE is_deleted = 0 AND (
            name_normalized LIKE :pattern ESCAPE '\' OR
            barcode_normalized LIKE :pattern ESCAPE '\'
        )
        ORDER BY CASE
            WHEN barcode_normalized = :query THEN 0
            WHEN name_normalized = :query THEN 1
            WHEN barcode_normalized LIKE :barcodePrefix ESCAPE '\' THEN 2
            WHEN name_normalized LIKE :namePrefix ESCAPE '\' THEN 3
            ELSE 4
        END,
        id ASC
        """,
    )
    fun observeSearch(
        query: String,
        pattern: String,
        barcodePrefix: String,
        namePrefix: String,
    ): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id AND is_deleted = 0")
    fun observeById(id: String): Flow<ProductEntity?>

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProductEntity?

    @Query(
        """
        SELECT * FROM products
        WHERE is_deleted = 0
          AND barcode_normalized = :barcode
        LIMIT 1
        """,
    )
    suspend fun findActiveByBarcode(barcode: String): ProductEntity?

    @Query(
        """
        SELECT * FROM products
        WHERE is_deleted = 0
          AND barcode_normalized = :barcode
          AND id != :excludeId
        LIMIT 1
        """,
    )
    suspend fun findActiveByBarcode(barcode: String, excludeId: String): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(product: ProductEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(products: List<ProductEntity>)

    @Update
    suspend fun update(product: ProductEntity)

    @Query(
        """
        UPDATE products
        SET is_deleted = 1, updated_at = :now, version = version + 1
        WHERE id = :id AND is_deleted = 0
        """,
    )
    suspend fun softDelete(id: String, now: Long): Int
}
