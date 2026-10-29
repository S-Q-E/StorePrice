package com.storeprice.app.data.repository

import com.storeprice.app.core.SearchText
import com.storeprice.app.data.local.ProductDao
import com.storeprice.app.data.local.ProductEntity
import com.storeprice.app.domain.model.Product
import com.storeprice.app.domain.validation.ProductValidator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SaveProductResult {
    data class Success(val id: String) : SaveProductResult
    data object DuplicateBarcode : SaveProductResult
    data object NotFound : SaveProductResult
}

interface ProductRepository {
    fun observeProducts(query: String): Flow<List<Product>>
    fun observeProduct(id: String): Flow<Product?>
    suspend fun save(
        id: String?,
        name: String,
        priceKzt: Long,
        barcode: String?,
    ): SaveProductResult
    suspend fun delete(id: String): Boolean
}

class RoomProductRepository(
    private val dao: ProductDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : ProductRepository {

    /**
     * Serializes local writes so the "check barcode, then insert" sequence cannot race
     * inside the app. The server will enforce the same rule with a database constraint.
     */
    private val writeMutex = Mutex()

    override fun observeProducts(query: String): Flow<List<Product>> {
        val normalized = SearchText.normalize(query)
        val flow = if (normalized.isEmpty()) {
            dao.observeAll()
        } else {
            val escaped = SearchText.escapeLike(normalized)
            dao.observeSearch(
                query = normalized,
                pattern = "%$escaped%",
                barcodePrefix = "$escaped%",
                namePrefix = "$escaped%",
            )
        }
        return flow.map { entities -> entities.map { it.toDomain() } }
    }

    override fun observeProduct(id: String): Flow<Product?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun save(
        id: String?,
        name: String,
        priceKzt: Long,
        barcode: String?,
    ): SaveProductResult = writeMutex.withLock {
        val cleanName = SearchText.normalizeDisplay(name)
        require(cleanName.isNotBlank()) { "Product name must not be blank" }
        require(priceKzt >= 0) { "Price must not be negative" }

        val cleanBarcode = barcode?.trim()?.takeIf { it.isNotEmpty() }
        require(cleanBarcode == null || cleanBarcode.length <= ProductValidator.MAX_BARCODE_LENGTH) {
            "Barcode is too long"
        }
        val barcodeNormalized = cleanBarcode?.lowercase(Locale.ROOT)
        val nameNormalized = SearchText.normalize(cleanName)
        val now = clock()

        if (id == null) {
            if (barcodeNormalized != null && hasBarcodeConflict(barcodeNormalized, excludeId = "")) {
                return@withLock SaveProductResult.DuplicateBarcode
            }
            val entity = ProductEntity(
                id = idGenerator(),
                barcode = cleanBarcode,
                barcodeNormalized = barcodeNormalized,
                name = cleanName,
                nameNormalized = nameNormalized,
                priceKzt = priceKzt,
                isDeleted = false,
                createdAt = now,
                updatedAt = now,
                version = 1,
            )
            dao.insert(entity)
            return@withLock SaveProductResult.Success(entity.id)
        }

        val existing = dao.getById(id) ?: return@withLock SaveProductResult.NotFound
        if (existing.isDeleted) return@withLock SaveProductResult.NotFound

        if (barcodeNormalized != null && hasBarcodeConflict(barcodeNormalized, excludeId = id)) {
            return@withLock SaveProductResult.DuplicateBarcode
        }

        dao.update(
            existing.copy(
                barcode = cleanBarcode,
                barcodeNormalized = barcodeNormalized,
                name = cleanName,
                nameNormalized = nameNormalized,
                priceKzt = priceKzt,
                updatedAt = now,
                version = existing.version + 1,
            ),
        )
        SaveProductResult.Success(id)
    }

    override suspend fun delete(id: String): Boolean = writeMutex.withLock {
        dao.softDelete(id, clock()) > 0
    }

    private suspend fun hasBarcodeConflict(
        barcodeNormalized: String,
        excludeId: String,
    ): Boolean = dao.findActiveByBarcode(barcodeNormalized, excludeId) != null
}

private fun ProductEntity.toDomain(): Product = Product(
    id = id,
    barcode = barcode,
    name = name,
    priceKzt = priceKzt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    version = version,
)
