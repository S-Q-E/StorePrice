package com.storeprice.app.data.repository

import com.storeprice.app.core.SearchText
import com.storeprice.app.data.local.ProductDao
import com.storeprice.app.data.local.ProductEntity
import com.storeprice.app.domain.model.Product
import com.storeprice.app.domain.validation.ProductValidator
import java.text.Collator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SaveProductResult {
    data class Success(val id: String) : SaveProductResult
    data object DuplicateBarcode : SaveProductResult
    data object NotFound : SaveProductResult
    data object VersionConflict : SaveProductResult
    data object NetworkUnavailable : SaveProductResult
    data object Forbidden : SaveProductResult
    data object ServerError : SaveProductResult
}

sealed interface CatalogSyncResult {
    data object Success : CatalogSyncResult
    data object NotConfigured : CatalogSyncResult
    data object Unauthorized : CatalogSyncResult
    data object NetworkUnavailable : CatalogSyncResult
    data object ServerError : CatalogSyncResult
}

/**
 * Observable synchronization state shared by the catalog screen and the
 * background refresh triggers.
 *
 * [lastSyncedAtMillis] survives process restarts in private preferences, while
 * [lastResult] describes the most recent attempt and [isSyncing] is true only
 * while a request is in flight.
 */
data class CatalogSyncState(
    val isSyncing: Boolean = false,
    val lastSyncedAtMillis: Long? = null,
    val lastResult: CatalogSyncResult? = null,
)

interface ProductRepository {
    fun observeProducts(query: String): Flow<List<Product>>
    fun observeProduct(id: String): Flow<Product?>

    /**
     * Finds an active product by an exact barcode.
     *
     * The lookup ignores case, trims the scanned value and never returns soft-deleted
     * products, so the scanner can reuse a barcode that was previously deleted.
     */
    suspend fun findActiveByBarcode(barcode: String): Product?

    suspend fun save(
        id: String?,
        name: String,
        priceKzt: Long,
        barcode: String?,
    ): SaveProductResult
    suspend fun delete(id: String): Boolean

    /**
     * Refreshes the local Room cache from the shared catalog.
     *
     * Local-only repositories do nothing. The UI keeps observing Room before,
     * during and after the request.
     */
    suspend fun syncCatalog(): CatalogSyncResult = CatalogSyncResult.NotConfigured

    /**
     * Exposes synchronization progress and the last confirmed server sync time.
     *
     * Local-only repositories return an idle state, so the same UI works without
     * Supabase configuration.
     */
    fun observeSyncState(): Flow<CatalogSyncState> = flowOf(CatalogSyncState())
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
        return flow.map { entities ->
            entities
                .sortedWith(productComparator(normalized.takeIf { it.isNotEmpty() }))
                .map { it.toDomain() }
        }
    }

    override fun observeProduct(id: String): Flow<Product?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun findActiveByBarcode(barcode: String): Product? {
        val cleanBarcode = barcode.trim().takeIf { it.isNotEmpty() } ?: return null
        if (cleanBarcode.length > ProductValidator.MAX_BARCODE_LENGTH) return null
        return dao.findActiveByBarcode(cleanBarcode.lowercase(Locale.ROOT))?.toDomain()
    }

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

    /**
     * Orders products by the search priority (when [query] is present) and then by
     * a Russian/Kazakh collation of the display name. SQLite's binary `ORDER BY`
     * puts Kazakh letters after `я`, so name ordering must happen in Kotlin.
     */
    private fun productComparator(query: String?): Comparator<ProductEntity> {
        val collator = createCollator()
        return Comparator { left, right ->
            if (query != null) {
                val rankComparison = searchRank(left, query).compareTo(searchRank(right, query))
                if (rankComparison != 0) return@Comparator rankComparison
            }
            val nameComparison = collator.compare(
                collationText(left.name),
                collationText(right.name),
            )
            if (nameComparison != 0) nameComparison else left.id.compareTo(right.id)
        }
    }

    /**
     * Russian collation rules do not know the Kazakh letters and place them after `я`
     * on JVM hosts. Folding each letter to the closest Russian one keeps the Kazakh
     * alphabet order while still using a [Collator] for the comparison.
     */
    private fun collationText(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char.lowercaseChar()) {
                    'ә' -> 'а'
                    'ғ' -> 'г'
                    'қ' -> 'к'
                    'ң' -> 'н'
                    'ө' -> 'о'
                    'ұ' -> 'у'
                    'ү' -> 'у'
                    'һ' -> 'х'
                    'і' -> 'и'
                    else -> char
                },
            )
        }
    }

    private fun searchRank(entity: ProductEntity, query: String): Int = when {
        entity.barcodeNormalized == query -> 0
        entity.nameNormalized == query -> 1
        entity.barcodeNormalized?.startsWith(query) == true -> 2
        entity.nameNormalized.startsWith(query) -> 3
        else -> 4
    }

    private fun createCollator(): Collator {
        val collator = try {
            Collator.getInstance(Locale("ru", "KZ"))
        } catch (_: RuntimeException) {
            Collator.getInstance(Locale("ru"))
        }
        collator.strength = Collator.SECONDARY
        return collator
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
