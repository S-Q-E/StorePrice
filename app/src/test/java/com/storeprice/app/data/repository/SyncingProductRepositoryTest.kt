package com.storeprice.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storeprice.app.data.local.StorePriceDatabase
import com.storeprice.app.data.remote.ProductRemote
import com.storeprice.app.data.remote.ProductRemoteException
import com.storeprice.app.data.remote.RemoteProduct
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncingProductRepositoryTest {

    private lateinit var database: StorePriceDatabase
    private lateinit var local: ProductRepository
    private lateinit var remote: FakeProductRemote
    private lateinit var repository: SyncingProductRepository
    private var now = 1_000L
    private var nextId = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, StorePriceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        local = RoomProductRepository(
            dao = database.productDao(),
            clock = { now },
            idGenerator = { "local-${nextId++}" },
        )
        remote = FakeProductRemote()
        repository = SyncingProductRepository(
            local = local,
            dao = database.productDao(),
            database = database,
            remote = remote,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `sync replaces the cached server snapshot`() = runBlocking {
        remote.products = listOf(
            remoteProduct(id = "server-1", name = "Серверный товар", barcode = "00123"),
            remoteProduct(id = "server-2", name = "Удалённый товар", isDeleted = true),
        )
        assertEquals(CatalogSyncResult.Success, repository.syncCatalog())

        remote.products = listOf(
            remoteProduct(id = "server-1", name = "Серверный товар", isDeleted = true),
            remoteProduct(id = "server-3", name = "Новый товар"),
        )
        assertEquals(CatalogSyncResult.Success, repository.syncCatalog())

        val products = repository.observeProducts("").first()
        assertEquals(listOf("server-3"), products.map { it.id })
        assertTrue(database.productDao().getById("server-1")?.isDeleted == true)
        assertNull(database.productDao().getById("server-2"))
    }

    @Test
    fun `failed sync keeps the local cache`() = runBlocking {
        local.save(null, "Локальный товар", 100, null)
        remote.fetchError = ProductRemoteException.NetworkUnavailable

        assertEquals(CatalogSyncResult.NetworkUnavailable, repository.syncCatalog())
        assertEquals("Локальный товар", repository.observeProducts("").first().single().name)
    }

    @Test
    fun `legacy local products are uploaded once before the cache is replaced`() = runBlocking {
        local.save(null, "Локальный товар", 100, "00123")

        assertEquals(CatalogSyncResult.Success, repository.syncCatalog())

        assertEquals("Локальный товар", remote.lastCreate?.name)
        assertEquals("00123", remote.lastCreate?.barcode)
        assertEquals(listOf("server-created"), repository.observeProducts("").first().map { it.id })
        assertEquals(1, remote.products.size)
    }

    @Test
    fun `active server barcode wins over a legacy local duplicate`() = runBlocking {
        local.save(null, "Старый локальный товар", 100, "00123")
        remote.products = listOf(
            remoteProduct(id = "server-1", name = "Серверный товар", barcode = "00123"),
        )

        assertEquals(CatalogSyncResult.Success, repository.syncCatalog())

        assertNull(remote.lastCreate)
        assertEquals(listOf("server-1"), repository.observeProducts("").first().map { it.id })
    }

    @Test
    fun `create sends normalized input to the server and caches the result`() = runBlocking {
        remote.createResult = { name, price, barcode ->
            remoteProduct(id = "server-1", name = name, priceKzt = price, barcode = barcode)
        }

        val result = repository.save(
            id = null,
            name = "  Молоко   3,2% ",
            priceKzt = 1_250,
            barcode = " 00123 ",
        )

        assertEquals(SaveProductResult.Success("server-1"), result)
        assertEquals("Молоко 3,2%", remote.lastCreate?.name)
        assertEquals(1_250L, remote.lastCreate?.priceKzt)
        assertEquals("00123", remote.lastCreate?.barcode)

        val product = repository.observeProduct("server-1").first()
        assertEquals("Молоко 3,2%", product?.name)
        assertEquals("00123", product?.barcode)
    }

    @Test
    fun `update sends the local version and caches the new version`() = runBlocking {
        remote.products = listOf(remoteProduct(id = "server-1", name = "Старое", version = 7))
        repository.syncCatalog()
        remote.updateResult = { id, expectedVersion, name, price, barcode ->
            remoteProduct(
                id = id,
                name = name,
                priceKzt = price,
                barcode = barcode,
                version = expectedVersion + 1,
            )
        }

        val result = repository.save(
            id = "server-1",
            name = "Новое",
            priceKzt = 2_000,
            barcode = "98765",
        )

        assertEquals(SaveProductResult.Success("server-1"), result)
        assertEquals(7L, remote.lastUpdate?.expectedVersion)
        assertEquals(8L, repository.observeProduct("server-1").first()?.version)
    }

    @Test
    fun `duplicate barcode is mapped for the editor`() = runBlocking {
        remote.createError = ProductRemoteException.DuplicateBarcode

        assertEquals(
            SaveProductResult.DuplicateBarcode,
            repository.save(null, "Товар", 100, "00123"),
        )
        assertTrue(repository.observeProducts("").first().isEmpty())
    }

    @Test
    fun `version conflict is mapped for the editor`() = runBlocking {
        remote.products = listOf(remoteProduct(id = "server-1", name = "Товар", version = 2))
        repository.syncCatalog()
        remote.updateError = ProductRemoteException.VersionConflict

        assertEquals(
            SaveProductResult.VersionConflict,
            repository.save("server-1", "Новое имя", 100, null),
        )
    }

    @Test
    fun `network failure is mapped for the editor`() = runBlocking {
        remote.createError = ProductRemoteException.NetworkUnavailable

        assertEquals(
            SaveProductResult.NetworkUnavailable,
            repository.save(null, "Товар", 100, null),
        )
    }

    @Test
    fun `delete uses the local version and marks the cache row deleted`() = runBlocking {
        remote.products = listOf(remoteProduct(id = "server-1", name = "Товар", version = 3))
        repository.syncCatalog()
        remote.softDeleteResult = { id, expectedVersion ->
            remoteProduct(id = id, name = "Товар", version = expectedVersion + 1, isDeleted = true)
        }

        assertTrue(repository.delete("server-1"))
        assertEquals(3L, remote.lastDelete?.expectedVersion)
        assertNull(repository.observeProduct("server-1").first())
        assertTrue(database.productDao().getById("server-1")?.isDeleted == true)
    }

    @Test
    fun `failed remote delete keeps the cached product`() = runBlocking {
        remote.products = listOf(remoteProduct(id = "server-1", name = "Товар", version = 3))
        repository.syncCatalog()
        remote.softDeleteError = ProductRemoteException.NetworkUnavailable

        assertFalse(repository.delete("server-1"))
        assertEquals("Товар", repository.observeProduct("server-1").first()?.name)
    }

    private class FakeProductRemote : ProductRemote {
        var products: List<RemoteProduct> = emptyList()
        var fetchError: ProductRemoteException? = null
        var createError: ProductRemoteException? = null
        var updateError: ProductRemoteException? = null
        var softDeleteError: ProductRemoteException? = null
        var createResult: (String, Long, String?) -> RemoteProduct = { name, price, barcode ->
            remoteProduct(id = "server-created", name = name, priceKzt = price, barcode = barcode)
        }
        var updateResult: (String, Long, String, Long, String?) -> RemoteProduct =
            { id, expectedVersion, name, price, barcode ->
                remoteProduct(
                    id = id,
                    name = name,
                    priceKzt = price,
                    barcode = barcode,
                    version = expectedVersion + 1,
                )
            }
        var softDeleteResult: (String, Long) -> RemoteProduct = { id, expectedVersion ->
            remoteProduct(id = id, name = "Товар", version = expectedVersion + 1, isDeleted = true)
        }

        var lastCreate: CreateCall? = null
            private set
        var lastUpdate: UpdateCall? = null
            private set
        var lastDelete: DeleteCall? = null
            private set

        override suspend fun fetchAll(): List<RemoteProduct> {
            fetchError?.let { throw it }
            return products
        }

        override suspend fun create(
            name: String,
            priceKzt: Long,
            barcode: String?,
        ): RemoteProduct {
            createError?.let { throw it }
            lastCreate = CreateCall(name, priceKzt, barcode)
            val created = createResult(name, priceKzt, barcode)
            products = products + created
            return created
        }

        override suspend fun update(
            id: String,
            expectedVersion: Long,
            name: String,
            priceKzt: Long,
            barcode: String?,
        ): RemoteProduct {
            updateError?.let { throw it }
            lastUpdate = UpdateCall(id, expectedVersion, name, priceKzt, barcode)
            val updated = updateResult(id, expectedVersion, name, priceKzt, barcode)
            products = products.map { if (it.id == id) updated else it }
            return updated
        }

        override suspend fun softDelete(
            id: String,
            expectedVersion: Long,
        ): RemoteProduct {
            softDeleteError?.let { throw it }
            lastDelete = DeleteCall(id, expectedVersion)
            val deleted = softDeleteResult(id, expectedVersion)
            products = products.map { if (it.id == id) deleted else it }
            return deleted
        }
    }

    private data class CreateCall(
        val name: String,
        val priceKzt: Long,
        val barcode: String?,
    )

    private data class UpdateCall(
        val id: String,
        val expectedVersion: Long,
        val name: String,
        val priceKzt: Long,
        val barcode: String?,
    )

    private data class DeleteCall(
        val id: String,
        val expectedVersion: Long,
    )

    private companion object {
        fun remoteProduct(
            id: String,
            name: String,
            priceKzt: Long = 100,
            barcode: String? = null,
            isDeleted: Boolean = false,
            version: Long = 1,
            createdAt: Long = 1_000,
            updatedAt: Long = 1_000,
        ) = RemoteProduct(
            id = id,
            barcode = barcode,
            name = name,
            priceKzt = priceKzt,
            isDeleted = isDeleted,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
        )
    }
}
