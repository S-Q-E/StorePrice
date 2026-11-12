package com.storeprice.app.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.storeprice.app.data.local.ProductDao
import com.storeprice.app.data.local.StorePriceDatabase
import com.storeprice.app.data.remote.ProductRemote
import com.storeprice.app.data.remote.ProductRemoteException
import com.storeprice.app.data.remote.RemoteProduct
import com.storeprice.app.data.sync.SyncMetadataStore
import com.storeprice.app.domain.model.Product
import com.storeprice.app.domain.validation.ProductValidationResult
import com.storeprice.app.domain.validation.ProductValidator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounded exponential backoff for read-only synchronization requests.
 *
 * UI writes are not retried inside a single operation: a create response can be
 * lost after the server committed it, so a blind retry could duplicate a
 * product. Only idempotent catalog reads use this policy. Legacy uploads are
 * retried on the next full sync with a stable client id.
 */
internal data class SyncRetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMillis: Long = 500L,
    val maxDelayMillis: Long = 4_000L,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be positive" }
        require(initialDelayMillis >= 0L) { "initialDelayMillis must not be negative" }
        require(maxDelayMillis >= initialDelayMillis) {
            "maxDelayMillis must be greater than or equal to initialDelayMillis"
        }
    }

    fun delayFor(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 20)
        return (initialDelayMillis * (1L shl exponent)).coerceAtMost(maxDelayMillis)
    }
}

/**
 * Catalog repository with Room as the UI source and Supabase RPCs as the write
 * target. Reads are always local; [syncCatalog] refreshes Room from the server.
 *
 * The first successful sync replaces the local snapshot. Later syncs request
 * only rows at or after the stored `updated_at` cursor and upsert them, so a
 * normal refresh does not download the whole catalog. A full sync is repeated
 * when the cursor is missing or the local product table is empty.
 */
internal class SyncingProductRepository(
    private val local: ProductRepository,
    private val dao: ProductDao,
    private val database: StorePriceDatabase,
    private val remote: ProductRemote?,
    private val metadata: SyncMetadataStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryPolicy: SyncRetryPolicy = SyncRetryPolicy(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : ProductRepository {

    private val writeMutex = Mutex()
    private val _syncState = MutableStateFlow(
        CatalogSyncState(lastSyncedAtMillis = metadata.lastSyncedAtMillis),
    )

    override fun observeProducts(query: String): Flow<List<Product>> =
        local.observeProducts(query)

    override fun observeProduct(id: String): Flow<Product?> =
        local.observeProduct(id)

    override fun observeSyncState(): StateFlow<CatalogSyncState> = _syncState.asStateFlow()

    override suspend fun findActiveByBarcode(barcode: String): Product? =
        local.findActiveByBarcode(barcode)

    override suspend fun save(
        id: String?,
        name: String,
        priceKzt: Long,
        barcode: String?,
    ): SaveProductResult = writeMutex.withLock {
        val remote = remote ?: return local.save(id, name, priceKzt, barcode)

        val draft = when (
            val validation = ProductValidator.validate(
                nameInput = name,
                priceInput = priceKzt.toString(),
                barcodeInput = barcode.orEmpty(),
            )
        ) {
            is ProductValidationResult.Valid -> validation.draft
            is ProductValidationResult.Invalid -> error("Invalid product input")
        }

        try {
            val saved = if (id == null) {
                remote.create(
                    name = draft.name,
                    priceKzt = draft.priceKzt,
                    barcode = draft.barcode,
                )
            } else {
                val existing = dao.getById(id) ?: return SaveProductResult.NotFound
                if (existing.isDeleted) return SaveProductResult.NotFound
                remote.update(
                    id = id,
                    expectedVersion = existing.version,
                    name = draft.name,
                    priceKzt = draft.priceKzt,
                    barcode = draft.barcode,
                )
            }
            dao.upsert(saved.toEntity())
            SaveProductResult.Success(saved.id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: ProductRemoteException) {
            error.toSaveResult()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to cache the saved product", error)
            SaveProductResult.ServerError
        }
    }

    override suspend fun delete(id: String): Boolean = writeMutex.withLock {
        val remote = remote ?: return local.delete(id)
        val existing = dao.getById(id) ?: return false
        if (existing.isDeleted) return false

        try {
            val deleted = remote.softDelete(id, existing.version)
            dao.upsert(deleted.toEntity())
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: ProductRemoteException) {
            false
        } catch (error: Exception) {
            Log.e(TAG, "Failed to cache the deleted product", error)
            false
        }
    }

    override suspend fun syncCatalog(): CatalogSyncResult = writeMutex.withLock {
        val remote = remote ?: return CatalogSyncResult.NotConfigured
        _syncState.update { it.copy(isSyncing = true) }

        val result = try {
            val cursor = metadata.cursorMillis
            if (cursor == null || dao.getAll().isEmpty()) {
                fullSync(remote)
            } else {
                incrementalSync(remote, cursor)
            }
        } catch (cancellation: CancellationException) {
            _syncState.update { it.copy(isSyncing = false) }
            throw cancellation
        } catch (error: ProductRemoteException) {
            error.toSyncResult()
        } catch (error: Exception) {
            Log.e(TAG, "Catalog synchronization failed", error)
            CatalogSyncResult.ServerError
        }

        if (result == CatalogSyncResult.Success) {
            val syncedAt = clock()
            metadata.lastSyncedAtMillis = syncedAt
            _syncState.update {
                it.copy(
                    isSyncing = false,
                    lastSyncedAtMillis = syncedAt,
                    lastResult = result,
                )
            }
        } else {
            _syncState.update {
                it.copy(isSyncing = false, lastResult = result)
            }
        }
        result
    }

    /**
     * Replaces the local snapshot with the complete server catalog.
     *
     * Products created before the shared catalog was connected have no server
     * row yet. They are uploaded once instead of silently dropping the local
     * catalog; server rows always win when a barcode already exists.
     */
    private suspend fun fullSync(remote: ProductRemote): CatalogSyncResult {
        val serverProducts = withReadRetry { remote.fetchAll() }
        val serverIds = serverProducts.mapTo(mutableSetOf()) { it.id }
        val activeBarcodes = serverProducts
            .asSequence()
            .filterNot { it.isDeleted }
            .mapNotNull { it.barcode?.lowercase(Locale.ROOT) }
            .toSet()

        var refreshSnapshot = false
        for (item in dao.getAll()) {
            if (item.isDeleted || item.id in serverIds) continue
            val normalizedBarcode = item.barcode?.lowercase(Locale.ROOT)
            if (normalizedBarcode != null && normalizedBarcode in activeBarcodes) continue

            val created = try {
                remote.create(
                    name = item.name,
                    priceKzt = item.priceKzt,
                    barcode = item.barcode,
                    // Reuse the local UUID as the server id. If the create
                    // response is lost, the next full sync returns this row
                    // instead of inserting a second legacy product.
                    clientId = item.id.asUuidOrNull(),
                )
            } catch (_: ProductRemoteException.DuplicateBarcode) {
                // Another device may have taken the barcode after our snapshot;
                // refresh so the server row replaces the legacy one.
                refreshSnapshot = true
                continue
            }

            refreshSnapshot = true
            database.withTransaction {
                dao.deleteById(item.id)
                dao.upsert(created.toEntity())
            }
        }

        val snapshot = if (refreshSnapshot) {
            withReadRetry { remote.fetchAll() }
        } else {
            serverProducts
        }
        database.withTransaction {
            dao.deleteAll()
            dao.insertAll(snapshot.map(RemoteProduct::toEntity))
        }
        metadata.cursorMillis = snapshot.maxOfOrNull { it.updatedAt }
        return CatalogSyncResult.Success
    }

    /**
     * Applies rows changed since the stored cursor without deleting local rows.
     *
     * Soft deletes are ordinary updated rows, so they arrive here as well. The
     * cursor only moves forward after the Room transaction succeeds.
     */
    private suspend fun incrementalSync(
        remote: ProductRemote,
        cursorMillis: Long,
    ): CatalogSyncResult {
        val changed = withReadRetry { remote.fetchSince(cursorMillis) }
        if (changed.isNotEmpty()) {
            database.withTransaction {
                changed.forEach { dao.upsert(it.toEntity()) }
            }
        }
        val newest = changed.maxOfOrNull { it.updatedAt }
        if (newest != null && newest > cursorMillis) {
            metadata.cursorMillis = newest
        }
        return CatalogSyncResult.Success
    }

    private suspend fun <T> withReadRetry(block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (error: ProductRemoteException) {
                if (!error.isRetryableRead() || attempt >= retryPolicy.maxAttempts) {
                    throw error
                }
                sleep(retryPolicy.delayFor(attempt))
                attempt++
            }
        }
    }

    private fun ProductRemoteException.isRetryableRead(): Boolean = when (this) {
        ProductRemoteException.NetworkUnavailable,
        ProductRemoteException.ServerError,
        -> true

        ProductRemoteException.Unauthorized,
        ProductRemoteException.DuplicateBarcode,
        ProductRemoteException.VersionConflict,
        ProductRemoteException.NotFound,
        -> false
    }

    private fun ProductRemoteException.toSyncResult(): CatalogSyncResult = when (this) {
        ProductRemoteException.Unauthorized -> CatalogSyncResult.Unauthorized
        ProductRemoteException.NetworkUnavailable -> CatalogSyncResult.NetworkUnavailable
        else -> CatalogSyncResult.ServerError
    }

    private fun ProductRemoteException.toSaveResult(): SaveProductResult = when (this) {
        ProductRemoteException.DuplicateBarcode -> SaveProductResult.DuplicateBarcode
        ProductRemoteException.VersionConflict -> SaveProductResult.VersionConflict
        ProductRemoteException.NotFound -> SaveProductResult.NotFound
        ProductRemoteException.NetworkUnavailable -> SaveProductResult.NetworkUnavailable
        ProductRemoteException.Unauthorized,
        ProductRemoteException.ServerError,
        -> SaveProductResult.ServerError
    }

    private fun String.asUuidOrNull(): String? =
        runCatching { UUID.fromString(this).toString() }.getOrNull()

    private companion object {
        const val TAG = "CatalogSync"
    }
}
