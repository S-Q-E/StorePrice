package com.storeprice.app.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.storeprice.app.data.local.ProductDao
import com.storeprice.app.data.local.StorePriceDatabase
import com.storeprice.app.data.remote.ProductRemote
import com.storeprice.app.data.remote.ProductRemoteException
import com.storeprice.app.data.remote.RemoteProduct
import com.storeprice.app.domain.model.Product
import com.storeprice.app.domain.validation.ProductValidationResult
import com.storeprice.app.domain.validation.ProductValidator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Catalog repository with Room as the UI source and Supabase RPCs as the write
 * target. Reads are always local; [syncCatalog] refreshes Room from the server.
 */
internal class SyncingProductRepository(
    private val local: ProductRepository,
    private val dao: ProductDao,
    private val database: StorePriceDatabase,
    private val remote: ProductRemote?,
) : ProductRepository {

    private val writeMutex = Mutex()

    override fun observeProducts(query: String): Flow<List<Product>> =
        local.observeProducts(query)

    override fun observeProduct(id: String): Flow<Product?> =
        local.observeProduct(id)

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
        try {
            val serverProducts = remote.fetchAll()
            val serverIds = serverProducts.mapTo(mutableSetOf()) { it.id }
            val activeBarcodes = serverProducts
                .asSequence()
                .filterNot { it.isDeleted }
                .mapNotNull { it.barcode?.lowercase(Locale.ROOT) }
                .toSet()

            // Products created before the shared catalog was connected have no
            // server row yet. Upload them once instead of silently dropping the
            // local catalog; server rows always win when a barcode already exists.
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
                    )
                } catch (_: ProductRemoteException.DuplicateBarcode) {
                    // Another device may have taken the barcode after our
                    // snapshot; refresh so the server row replaces the legacy one.
                    refreshSnapshot = true
                    continue
                }

                refreshSnapshot = true
                database.withTransaction {
                    dao.deleteById(item.id)
                    dao.upsert(created.toEntity())
                }
            }

            val snapshot = if (refreshSnapshot) remote.fetchAll() else serverProducts
            database.withTransaction {
                dao.deleteAll()
                dao.insertAll(snapshot.map(RemoteProduct::toEntity))
            }
            CatalogSyncResult.Success
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: ProductRemoteException) {
            when (error) {
                ProductRemoteException.Unauthorized -> CatalogSyncResult.Unauthorized
                ProductRemoteException.NetworkUnavailable -> CatalogSyncResult.NetworkUnavailable
                else -> CatalogSyncResult.ServerError
            }
        } catch (error: Exception) {
            Log.e(TAG, "Catalog synchronization failed", error)
            CatalogSyncResult.ServerError
        }
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

    private companion object {
        const val TAG = "CatalogSync"
    }
}
