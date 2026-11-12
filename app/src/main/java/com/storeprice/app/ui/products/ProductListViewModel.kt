package com.storeprice.app.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.storeprice.app.data.repository.CatalogSyncResult
import com.storeprice.app.data.repository.ProductRepository
import com.storeprice.app.domain.model.Product
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProductListUiState(
    val query: String = "",
    val products: List<Product> = emptyList(),
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val lastSyncedAtMillis: Long? = null,
    val syncResult: CatalogSyncResult? = null,
)

class ProductListViewModel(
    private val repository: ProductRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val _uiState = MutableStateFlow(ProductListUiState())
    val uiState: StateFlow<ProductListUiState> = _uiState.asStateFlow()
    private var syncJob: Job? = null

    init {
        viewModelScope.launch {
            query.collectLatest { value ->
                _uiState.update { it.copy(query = value) }
                repository.observeProducts(value).collect { products ->
                    _uiState.update {
                        it.copy(products = products, isLoading = false)
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.observeSyncState().collect { syncState ->
                _uiState.update {
                    it.copy(
                        isSyncing = syncState.isSyncing,
                        lastSyncedAtMillis = syncState.lastSyncedAtMillis,
                        syncResult = syncState.lastResult,
                    )
                }
            }
        }
        refresh()
    }

    fun onQueryChange(value: String) {
        query.value = value
        _uiState.update { it.copy(query = value) }
    }

    fun clearQuery() {
        onQueryChange("")
    }

    /** Runs a manual catalog refresh and ignores repeated taps while one is active. */
    fun refresh() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            repository.syncCatalog()
        }
    }

    companion object {
        fun factory(repository: ProductRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProductListViewModel(repository) }
        }
    }
}
