package com.storeprice.app.ui.product

import com.storeprice.app.data.repository.ProductRepository
import com.storeprice.app.data.repository.SaveProductResult
import com.storeprice.app.domain.model.Product
import com.storeprice.app.domain.validation.ProductFieldError
import com.storeprice.app.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProductEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `invalid form is not sent to repository`() = runTest {
        val repository = FakeProductRepository()
        val viewModel = ProductEditorViewModel(repository, productId = null)

        viewModel.onPriceChange("100")
        viewModel.save()

        assertEquals(ProductFieldError.NAME_REQUIRED, viewModel.uiState.value.nameError)
        assertNull(repository.lastSave)
        assertFalse(viewModel.uiState.value.saved)
    }

    @Test
    fun `valid form is saved and marked as completed`() = runTest {
        val repository = FakeProductRepository(
            saveResult = SaveProductResult.Success("new-id"),
        )
        val viewModel = ProductEditorViewModel(repository, productId = null)

        viewModel.onNameChange("  Молоко  ")
        viewModel.onPriceChange("1 250")
        viewModel.onBarcodeChange("00123")
        viewModel.save()

        assertTrue(viewModel.uiState.value.saved)
        assertEquals("new-id", viewModel.uiState.value.savedProductId)
        assertEquals("Молоко", repository.lastSave?.name)
        assertEquals(1_250L, repository.lastSave?.priceKzt)
        assertEquals("00123", repository.lastSave?.barcode)
    }

    @Test
    fun `duplicate barcode is shown on the barcode field`() = runTest {
        val repository = FakeProductRepository(
            saveResult = SaveProductResult.DuplicateBarcode,
        )
        val viewModel = ProductEditorViewModel(repository, productId = null)

        viewModel.onNameChange("Молоко")
        viewModel.onPriceChange("100")
        viewModel.onBarcodeChange("111")
        viewModel.save()

        assertEquals(ProductFieldError.BARCODE_DUPLICATE, viewModel.uiState.value.barcodeError)
        assertFalse(viewModel.uiState.value.saved)
    }

    @Test
    fun `scanned barcode is prefilled for a new product`() = runTest {
        val repository = FakeProductRepository(
            saveResult = SaveProductResult.Success("new-id"),
        )
        val viewModel = ProductEditorViewModel(
            repository = repository,
            productId = null,
            initialBarcode = " 00123 ",
        )

        assertEquals("00123", viewModel.uiState.value.barcode)
        viewModel.onNameChange("Молоко")
        viewModel.onPriceChange("100")
        viewModel.save()

        assertTrue(viewModel.uiState.value.saved)
        assertEquals("00123", repository.lastSave?.barcode)
    }

    @Test
    fun `existing product is loaded into the form`() = runTest {
        val product = Product(
            id = "existing-id",
            barcode = "00123",
            name = "Молоко",
            priceKzt = 1_250,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            version = 1L,
        )
        val repository = FakeProductRepository(product = product)

        val viewModel = ProductEditorViewModel(repository, productId = product.id)

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("Молоко", viewModel.uiState.value.name)
        assertEquals("1250", viewModel.uiState.value.price)
        assertEquals("00123", viewModel.uiState.value.barcode)
    }

    @Test
    fun `missing product shows not found instead of an empty form`() = runTest {
        val viewModel = ProductEditorViewModel(
            repository = FakeProductRepository(product = null),
            productId = "missing",
        )

        assertNotNull(viewModel.uiState.value.formError)
        assertEquals(EditorFormError.PRODUCT_NOT_FOUND, viewModel.uiState.value.formError)
    }

    private class FakeProductRepository(
        private val saveResult: SaveProductResult = SaveProductResult.Success("id"),
        private val product: Product? = null,
    ) : ProductRepository {

        var lastSave: SaveCall? = null
            private set

        override fun observeProducts(query: String): Flow<List<Product>> = flowOf(emptyList())

        override fun observeProduct(id: String): Flow<Product?> = flowOf(product)

        override suspend fun findActiveByBarcode(barcode: String): Product? = product

        override suspend fun save(
            id: String?,
            name: String,
            priceKzt: Long,
            barcode: String?,
        ): SaveProductResult {
            lastSave = SaveCall(id, name, priceKzt, barcode)
            return saveResult
        }

        override suspend fun delete(id: String): Boolean = true
    }

    private data class SaveCall(
        val id: String?,
        val name: String,
        val priceKzt: Long,
        val barcode: String?,
    )
}
