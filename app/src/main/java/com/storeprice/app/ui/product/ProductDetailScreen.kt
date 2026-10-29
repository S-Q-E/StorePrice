package com.storeprice.app.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storeprice.app.R
import com.storeprice.app.core.formatKzt
import com.storeprice.app.core.formatTimestamp
import com.storeprice.app.domain.model.Product
import com.storeprice.app.ui.rememberProductRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    productId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProductDetailViewModel = viewModel(
        factory = ProductDetailViewModel.factory(
            repository = rememberProductRepository(),
            productId = productId,
        ),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onDeleted()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.product_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    val product = uiState.product
                    if (product != null) {
                        IconButton(onClick = { onEdit(product.id) }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            uiState.product == null -> ProductNotFound(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onBack = onBack,
            )

            else -> ProductDetails(
                product = requireNotNull(uiState.product),
                isDeleting = uiState.isDeleting,
                deleteFailed = uiState.deleteFailed,
                onEdit = { onEdit(requireNotNull(uiState.product).id) },
                onDelete = { showDeleteDialog = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    val product = uiState.product
    if (showDeleteDialog && product != null) {
        AlertDialog(
            onDismissRequest = {
                if (!uiState.isDeleting) showDeleteDialog = false
            },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, product.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.delete()
                    },
                    enabled = !uiState.isDeleting,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        text = stringResource(
                            if (uiState.isDeleting) R.string.deleting else R.string.delete,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    enabled = !uiState.isDeleting,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ProductDetails(
    product: Product,
    isDeleting: Boolean,
    deleteFailed: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = product.name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Text(
            text = formatKzt(product.priceKzt),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(4.dp))

        InfoRow(
            label = stringResource(R.string.barcode_detail_label),
            value = product.barcode ?: stringResource(R.string.no_barcode),
        )
        InfoRow(
            label = stringResource(R.string.last_updated_label),
            value = formatTimestamp(product.updatedAt),
        )

        if (deleteFailed) {
            Text(
                text = stringResource(R.string.error_delete_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onEdit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !isDeleting,
        ) {
            Icon(imageVector = Icons.Default.Edit, contentDescription = null)
            Text(
                text = stringResource(R.string.edit),
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !isDeleting,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = null)
            Text(
                text = stringResource(R.string.delete),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ProductNotFound(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.product_not_found),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.product_not_found_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onBack) {
            Text(stringResource(R.string.back_to_catalog))
        }
    }
}
