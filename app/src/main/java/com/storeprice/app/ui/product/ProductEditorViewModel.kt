package com.storeprice.app.ui.product

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.storeprice.app.data.repository.ProductRepository
import com.storeprice.app.data.repository.SaveProductResult
import com.storeprice.app.domain.validation.ProductFieldError
import com.storeprice.app.domain.validation.ProductValidationResult
import com.storeprice.app.domain.validation.ProductValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EditorFormError {
    PRODUCT_NOT_FOUND,
    SAVE_FAILED,
    NETWORK_UNAVAILABLE,
    VERSION_CONFLICT,
}

data class ProductEditorUiState(
    val isLoading: Boolean,
    val isSaving: Boolean = false,
    val name: String = "",
    val price: String = "",
    val barcode: String = "",
    val nameError: ProductFieldError? = null,
    val priceError: ProductFieldError? = null,
    val barcodeError: ProductFieldError? = null,
    val formError: EditorFormError? = null,
    val saved: Boolean = false,
    val savedProductId: String? = null,
)

class ProductEditorViewModel(
    private val repository: ProductRepository,
    private val productId: String?,
    initialBarcode: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ProductEditorUiState(
            isLoading = productId != null,
            barcode = if (productId == null) initialBarcode?.trim().orEmpty() else "",
        ),
    )
    val uiState: StateFlow<ProductEditorUiState> = _uiState.asStateFlow()

    init {
        if (productId != null) {
            viewModelScope.launch {
                val product = repository.observeProduct(productId).first()
                _uiState.update {
                    if (product == null) {
                        it.copy(isLoading = false, formError = EditorFormError.PRODUCT_NOT_FOUND)
                    } else {
                        it.copy(
                            isLoading = false,
                            name = product.name,
                            price = product.priceKzt.toString(),
                            barcode = product.barcode.orEmpty(),
                        )
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) {
        _uiState.update {
            it.copy(name = value, nameError = null, formError = null)
        }
    }

    fun onPriceChange(value: String) {
        _uiState.update {
            it.copy(price = value, priceError = null, formError = null)
        }
    }

    fun onBarcodeChange(value: String) {
        _uiState.update {
            it.copy(barcode = value, barcodeError = null, formError = null)
        }
    }

    fun save() {
        val current = _uiState.value
        if (current.isSaving || current.saved) return

        when (val validation = ProductValidator.validate(
            nameInput = current.name,
            priceInput = current.price,
            barcodeInput = current.barcode,
        )) {
            is ProductValidationResult.Invalid -> {
                _uiState.update {
                    it.copy(
                        nameError = validation.nameError,
                        priceError = validation.priceError,
                        barcodeError = validation.barcodeError,
                    )
                }
            }

            is ProductValidationResult.Valid -> {
                _uiState.update { it.copy(isSaving = true, formError = null) }
                viewModelScope.launch {
                    val result = try {
                        repository.save(
                            id = productId,
                            name = validation.draft.name,
                            priceKzt = validation.draft.priceKzt,
                            barcode = validation.draft.barcode,
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        _uiState.update {
                            it.copy(isSaving = false, formError = EditorFormError.SAVE_FAILED)
                        }
                        return@launch
                    }

                    _uiState.update { state ->
                        when (result) {
                            is SaveProductResult.Success -> state.copy(
                                isSaving = false,
                                saved = true,
                                savedProductId = result.id,
                            )

                            SaveProductResult.DuplicateBarcode -> state.copy(
                                isSaving = false,
                                barcodeError = ProductFieldError.BARCODE_DUPLICATE,
                            )

                            SaveProductResult.NotFound -> state.copy(
                                isSaving = false,
                                formError = EditorFormError.PRODUCT_NOT_FOUND,
                            )

                            SaveProductResult.VersionConflict -> state.copy(
                                isSaving = false,
                                formError = EditorFormError.VERSION_CONFLICT,
                            )

                            SaveProductResult.NetworkUnavailable -> state.copy(
                                isSaving = false,
                                formError = EditorFormError.NETWORK_UNAVAILABLE,
                            )

                            SaveProductResult.ServerError -> state.copy(
                                isSaving = false,
                                formError = EditorFormError.SAVE_FAILED,
                            )
                        }
                    }

                    if (result is SaveProductResult.VersionConflict) {
                        try {
                            repository.syncCatalog()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            // The form keeps the user's input; the next save retries
                            // with the refreshed version if synchronization worked.
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun factory(
            repository: ProductRepository,
            productId: String?,
            initialBarcode: String? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProductEditorViewModel(repository, productId, initialBarcode) }
        }
    }
}
