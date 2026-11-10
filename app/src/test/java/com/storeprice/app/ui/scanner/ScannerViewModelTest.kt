package com.storeprice.app.ui.scanner

import com.storeprice.app.data.repository.ProductRepository
import com.storeprice.app.data.repository.SaveProductResult
import com.storeprice.app.domain.model.Product
import com.storeprice.app.testutil.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScannerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `found product opens its detail`() = runTest {
        val product = product(id = "product-1", barcode = "00123")
        val repository = FakeProductRepository(product = product)
        val viewModel = ScannerViewModel(repository)

        viewModel.onBarcodeDetected(" 00123 ")

        assertEquals(listOf("00123"), repository.lookups)
        assertEquals(
            ScannerDestination.ExistingProduct("product-1"),
            viewModel.uiState.value.destination,
        )
        assertFalse(viewModel.uiState.value.isProcessing)
        assertFalse(viewModel.uiState.value.lookupFailed)
    }

    @Test
    fun `unknown product opens the form with the scanned barcode`() = runTest {
        val repository = FakeProductRepository(product = null)
        val viewModel = ScannerViewModel(repository)

        viewModel.onBarcodeDetected("4600000000018")

        assertEquals(
            ScannerDestination.NewProduct("4600000000018"),
            viewModel.uiState.value.destination,
        )
    }

    @Test
    fun `blank detection is ignored`() = runTest {
        val repository = FakeProductRepository(product = null)
        val viewModel = ScannerViewModel(repository)

        viewModel.onBarcodeDetected("   ")

        assertTrue(repository.lookups.isEmpty())
        assertNull(viewModel.uiState.value.destination)
    }

    @Test
    fun `repeated detections cannot start another lookup or replace destination`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeProductRepository(product = null, gate = gate)
        val viewModel = ScannerViewModel(repository)

        viewModel.onBarcodeDetected("001")
        viewModel.onBarcodeDetected("002")

        assertTrue(viewModel.uiState.value.isProcessing)
        assertEquals(listOf("001"), repository.lookups)

        gate.complete(Unit)

        assertEquals(
            ScannerDestination.NewProduct("001"),
            viewModel.uiState.value.destination,
        )

        viewModel.onBarcodeDetected("003")

        assertEquals(listOf("001"), repository.lookups)
        assertEquals(
            ScannerDestination.NewProduct("001"),
            viewModel.uiState.value.destination,
        )
    }

    @Test
    fun `lookup failure requires an explicit retry`() = runTest {
        val repository = FakeProductRepository(
            product = product(id = "product-1", barcode = "001"),
            failuresBeforeSuccess = 1,
        )
        val viewModel = ScannerViewModel(repository)

        viewModel.onBarcodeDetected("001")

        assertTrue(viewModel.uiState.value.lookupFailed)
        assertNull(viewModel.uiState.value.destination)
        assertEquals(listOf("001"), repository.lookups)

        viewModel.onBarcodeDetected("001")
        assertEquals(listOf("001"), repository.lookups)

        viewModel.retryLookup()

        assertFalse(viewModel.uiState.value.lookupFailed)
        assertEquals(listOf("001", "001"), repository.lookups)
        assertEquals(
            ScannerDestination.ExistingProduct("product-1"),
            viewModel.uiState.value.destination,
        )
    }

    private fun product(id: String, barcode: String): Product = Product(
        id = id,
        barcode = barcode,
        name = "Молоко",
        priceKzt = 1_250,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        version = 1L,
    )

    private class FakeProductRepository(
        private val product: Product?,
        private val gate: CompletableDeferred<Unit>? = null,
        private var failuresBeforeSuccess: Int = 0,
    ) : ProductRepository {

        val lookups = mutableListOf<String>()

        override fun observeProducts(query: String): Flow<List<Product>> = flowOf(emptyList())

        override fun observeProduct(id: String): Flow<Product?> = flowOf(product)

        override suspend fun findActiveByBarcode(barcode: String): Product? {
            lookups += barcode
            gate?.await()
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess -= 1
                error("lookup failed")
            }
            return product
        }

        override suspend fun save(
            id: String?,
            name: String,
            priceKzt: Long,
            barcode: String?,
        ): SaveProductResult = SaveProductResult.Success("id")

        override suspend fun delete(id: String): Boolean = true
    }
}
