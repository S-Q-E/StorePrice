package com.storeprice.app.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.storeprice.app.data.repository.ProductRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ScannerDestination {
    data class ExistingProduct(val productId: String) : ScannerDestination
    data class NewProduct(val barcode: String) : ScannerDestination
}

data class ScannerUiState(
    val isProcessing: Boolean = false,
    val destination: ScannerDestination? = null,
    val lookupFailed: Boolean = false,
)

/**
 * Looks up one scanned barcode in the local catalog.
 *
 * The first detection locks the scan session: later frames cannot start another lookup or
 * replace the destination. A failed lookup must be retried explicitly.
 */
class ScannerViewModel(
    private val repository: ProductRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private var pendingBarcode: String? = null

    fun onBarcodeDetected(rawValue: String) {
        val barcode = rawValue.trim()
        if (barcode.isEmpty()) return

        val current = _uiState.value
        if (current.isProcessing || current.destination != null || current.lookupFailed) return

        launchLookup(barcode)
    }

    fun retryLookup() {
        if (!_uiState.value.lookupFailed) return
        val barcode = pendingBarcode ?: return
        launchLookup(barcode)
    }

    /** Сбрасывает результат, чтобы после сообщения «Товар не найден» можно было сканировать снова. */
    fun resetResult() {
        pendingBarcode = null
        _uiState.update {
            it.copy(isProcessing = false, destination = null, lookupFailed = false)
        }
    }

    private fun launchLookup(barcode: String) {
        if (_uiState.value.isProcessing || _uiState.value.destination != null) return

        pendingBarcode = barcode
        _uiState.update { it.copy(isProcessing = true, lookupFailed = false) }
        viewModelScope.launch {
            val product = try {
                repository.findActiveByBarcode(barcode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiState.update { it.copy(isProcessing = false, lookupFailed = true) }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isProcessing = false,
                    destination = if (product == null) {
                        ScannerDestination.NewProduct(barcode)
                    } else {
                        ScannerDestination.ExistingProduct(product.id)
                    },
                )
            }
        }
    }

    companion object {
        fun factory(repository: ProductRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScannerViewModel(repository) }
        }
    }
}
