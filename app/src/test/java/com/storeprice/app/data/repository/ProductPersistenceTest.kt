package com.storeprice.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storeprice.app.data.local.StorePriceDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductPersistenceTest {

    private val databaseName = "storeprice-persistence-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `products survive closing and reopening the database`() = runBlocking {
        var database = Room.databaseBuilder(
            context,
            StorePriceDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()

        var repository: ProductRepository = RoomProductRepository(
            dao = database.productDao(),
            clock = { 1_000L },
            idGenerator = { "persistent-id" },
        )
        repository.save(null, "Молоко", 1_250, "00123")
        database.close()

        database = Room.databaseBuilder(
            context,
            StorePriceDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()
        repository = RoomProductRepository(database.productDao())

        val products = repository.observeProducts("молоко").first()
        assertEquals(1, products.size)
        assertEquals("Молоко", products.single().name)
        assertEquals("00123", products.single().barcode)
        assertEquals(1_250L, products.single().priceKzt)

        database.close()
    }
}
