package com.storeprice.app.ui.product

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.storeprice.app.data.repository.ProductRepository
import com.storeprice.app.domain.model.Product
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProductDetailUiState(
    val isLoading: Boolean = true,
    val product: Product? = null,
    val isDeleting: Boolean = false,
    val deleted: Boolean = false,
    val deleteFailed: Boolean = false,
)

class ProductDetailViewModel(
    private val repository: ProductRepository,
    private val productId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProductDetailUiState())
    val uiState: StateFlow<ProductDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeProduct(productId).collect { product ->
                _uiState.update {
                    it.copy(isLoading = false, product = product)
                }
            }
        }
    }

    fun delete() {
        if (_uiState.value.isDeleting || _uiState.value.deleted) return
        _uiState.update { it.copy(isDeleting = true, deleteFailed = false) }
        viewModelScope.launch {
            val deleted = try {
                repository.delete(productId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            _uiState.update {
                it.copy(
                    isDeleting = false,
                    deleted = deleted,
                    deleteFailed = !deleted,
                )
            }
        }
    }

    companion object {
        fun factory(
            repository: ProductRepository,
            productId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProductDetailViewModel(repository, productId) }
        }
    }
}
