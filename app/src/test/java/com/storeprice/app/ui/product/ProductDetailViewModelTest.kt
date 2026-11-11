package com.storeprice.app.ui.product

import com.storeprice.app.data.repository.ProductRepository
import com.storeprice.app.data.repository.SaveProductResult
import com.storeprice.app.domain.model.Product
import com.storeprice.app.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProductDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val product = Product(
        id = "product-id",
        barcode = "00123",
        name = "Молоко",
        priceKzt = 1_250,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        version = 1L,
    )

    @Test
    fun `successful delete marks state deleted and keeps screen pending`() = runTest {
        val repository = FakeProductRepository(product)
        val viewModel = ProductDetailViewModel(repository, product.id)

        assertEquals(product, viewModel.uiState.value.product)

        viewModel.delete()

        val state = viewModel.uiState.value
        assertTrue(state.deleted)
        assertFalse(state.deleteFailed)
        assertFalse(state.isDeleting)
        assertEquals(ProductDetailContent.Pending, state.content())

        val deletingState = ProductDetailUiState(
            isLoading = false,
            product = null,
            isDeleting = true,
        )
        assertEquals(ProductDetailContent.Pending, deletingState.content())
    }

    private class FakeProductRepository(
        initialProduct: Product?,
    ) : ProductRepository {

        private val productFlow = MutableStateFlow(initialProduct)

        override fun observeProducts(query: String): Flow<List<Product>> =
            MutableStateFlow(emptyList())

        override fun observeProduct(id: String): Flow<Product?> = productFlow

        override suspend fun findActiveByBarcode(barcode: String): Product? = null

        override suspend fun save(
            id: String?,
            name: String,
            priceKzt: Long,
            barcode: String?,
        ): SaveProductResult = SaveProductResult.Success("saved-id")

        override suspend fun delete(id: String): Boolean {
            productFlow.value = null
            return true
        }
    }
}
