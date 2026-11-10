package com.storeprice.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.storeprice.app.core.SearchText
import com.storeprice.app.data.local.ProductEntity
import com.storeprice.app.data.local.StorePriceDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomProductRepositoryTest {

    private lateinit var database: StorePriceDatabase
    private lateinit var repository: ProductRepository
    private var now = 1_000L
    private var nextId = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, StorePriceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomProductRepository(
            dao = database.productDao(),
            clock = { now },
            idGenerator = { "id-${nextId++}" },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `created product is stored and visible in catalog`() = runBlocking {
        val result = repository.save(
            id = null,
            name = "  Молоко   3,2% ",
            priceKzt = 1_250,
            barcode = " 00123 ",
        )

        assertTrue(result is SaveProductResult.Success)
        val products = repository.observeProducts("").first()

        assertEquals(1, products.size)
        assertEquals("Молоко 3,2%", products.single().name)
        assertEquals("00123", products.single().barcode)
        assertEquals(1_250L, products.single().priceKzt)
        assertEquals(1L, products.single().version)
    }

    @Test
    fun `duplicate active barcode is rejected ignoring case`() = runBlocking {
        addProduct(name = "Первый", barcode = "ABC")

        val result = repository.save(null, "Второй", 500, "abc")

        assertEquals(SaveProductResult.DuplicateBarcode, result)
        assertEquals(1, repository.observeProducts("").first().size)
    }

    @Test
    fun `barcode can be reused after soft delete`() = runBlocking {
        val id = addProduct(name = "Старый", barcode = "001")

        assertTrue(repository.delete(id))
        assertNull(repository.observeProduct(id).first())
        assertEquals(0, repository.observeProducts("").first().size)

        val result = repository.save(null, "Новый", 700, "001")
        assertTrue(result is SaveProductResult.Success)
        assertEquals(listOf("Новый"), query(""))
    }

    @Test
    fun `active product can be found by scanned barcode ignoring case and spaces`() = runBlocking {
        val id = addProduct(name = "Молоко", barcode = "ABC-123")

        val product = requireNotNull(repository.findActiveByBarcode("  abc-123 "))

        assertEquals(id, product.id)
        assertEquals("ABC-123", product.barcode)
    }

    @Test
    fun `scanner lookup does not return deleted product`() = runBlocking {
        val id = addProduct(name = "Старый", barcode = "001")

        repository.delete(id)

        assertNull(repository.findActiveByBarcode("001"))
    }

    @Test
    fun `scanner lookup returns null for blank unknown and too long barcode`() = runBlocking {
        addProduct(name = "Молоко", barcode = "001")

        assertNull(repository.findActiveByBarcode("   "))
        assertNull(repository.findActiveByBarcode("002"))
        assertNull(repository.findActiveByBarcode("1".repeat(65)))
    }

    @Test
    fun `multiple products without barcode are allowed`() = runBlocking {
        addProduct(name = "Первый", barcode = null)
        addProduct(name = "Второй", barcode = "   ")

        assertEquals(2, repository.observeProducts("").first().size)
    }

    @Test
    fun `update changes fields and increments version`() = runBlocking {
        val id = addProduct(name = "Молоко", price = 1_000, barcode = "111")
        now = 2_000L

        val result = repository.save(id, "Молоко 2,5%", 1_300, null)

        assertTrue(result is SaveProductResult.Success)
        val product = requireNotNull(repository.observeProduct(id).first())
        assertEquals("Молоко 2,5%", product.name)
        assertEquals(1_300L, product.priceKzt)
        assertNull(product.barcode)
        assertEquals(2L, product.version)
        assertEquals(1_000L, product.createdAt)
        assertEquals(2_000L, product.updatedAt)
    }

    @Test
    fun `update to duplicate barcode is rejected without changing product`() = runBlocking {
        addProduct(name = "Первый", barcode = "111")
        val secondId = addProduct(name = "Второй", barcode = "222")

        val result = repository.save(secondId, "Второй", 100, "111")

        assertEquals(SaveProductResult.DuplicateBarcode, result)
        assertEquals("222", requireNotNull(repository.observeProduct(secondId).first()).barcode)
    }

    @Test
    fun `update of missing product returns not found`() = runBlocking {
        val result = repository.save("missing", "Товар", 100, null)
        assertEquals(SaveProductResult.NotFound, result)
    }

    @Test
    fun `deleted product is hidden from search`() = runBlocking {
        val visibleId = addProduct(name = "Молоко", barcode = "111")
        val deletedId = addProduct(name = "Молоко 3,2%", barcode = "222")

        repository.delete(deletedId)

        assertEquals(listOf("Молоко"), query("молоко"))
        assertTrue(repository.observeProduct(visibleId).first() != null)
        assertNull(repository.observeProduct(deletedId).first())
    }

    @Test
    fun `search is case insensitive and normalizes spaces`() = runBlocking {
        addProduct(name = "Молоко", barcode = "12345")

        assertEquals(listOf("Молоко"), query("  МОЛОКО   "))
    }

    @Test
    fun `search prioritizes exact name then prefix then contains`() = runBlocking {
        addProduct(name = "Молоко сгущённое", barcode = null)
        addProduct(name = "Молоко 3,2%", barcode = "999")
        addProduct(name = "Молоко", barcode = "12345")

        assertEquals(
            listOf("Молоко", "Молоко 3,2%", "Молоко сгущённое"),
            query("молоко"),
        )
    }

    @Test
    fun `search prioritizes exact barcode over prefix`() = runBlocking {
        addProduct(name = "Молоко", barcode = "12345")
        addProduct(name = "Хлеб", barcode = "123")

        assertEquals(listOf("Хлеб", "Молоко"), query("123"))
        assertEquals(listOf("Молоко"), query("12345"))
    }

    @Test
    fun `search finds product without barcode by name`() = runBlocking {
        addProduct(name = "Кофе молотый", barcode = null)

        assertEquals(listOf("Кофе молотый"), query("кофе"))
    }

    @Test
    fun `search treats like metacharacters literally`() = runBlocking {
        addProduct(name = "Скидка 100%", barcode = null)
        addProduct(name = "Товар 1000", barcode = null)
        addProduct(name = "Код_1", barcode = null)

        assertEquals(listOf("Скидка 100%"), query("100%"))
        assertEquals(listOf("Код_1"), query("_"))
    }

    @Test
    fun `search works on a catalog of five thousand products`() = runBlocking {
        val entities = (0 until 5_000).map { index ->
            val name = "Товар ${index.toString().padStart(4, '0')}"
            ProductEntity(
                id = "bulk-$index",
                barcode = "200${index.toString().padStart(5, '0')}",
                barcodeNormalized = "200${index.toString().padStart(5, '0')}",
                name = name,
                nameNormalized = SearchText.normalize(name),
                priceKzt = 100L + index,
                isDeleted = false,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                version = 1L,
            )
        }
        database.productDao().insertAll(entities)

        assertEquals(5_000, repository.observeProducts("").first().size)
        assertEquals(listOf("Товар 4999"), query("Товар 4999"))
    }

    private suspend fun addProduct(
        name: String,
        price: Long = 100,
        barcode: String? = null,
    ): String {
        val result = repository.save(null, name, price, barcode)
        return (result as SaveProductResult.Success).id
    }

    private suspend fun query(text: String): List<String> =
        repository.observeProducts(text).first().map { it.name }
}
