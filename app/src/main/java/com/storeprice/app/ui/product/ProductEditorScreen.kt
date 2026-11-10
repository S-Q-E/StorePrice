package com.storeprice.app.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storeprice.app.R
import com.storeprice.app.core.formatKzt
import com.storeprice.app.ui.rememberProductRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductEditorScreen(
    productId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialBarcode: String? = null,
    viewModel: ProductEditorViewModel = viewModel(
        factory = ProductEditorViewModel.factory(
            repository = rememberProductRepository(),
            productId = productId,
            initialBarcode = initialBarcode,
        ),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(uiState.savedProductId) {
        val savedProductId = uiState.savedProductId
        if (savedProductId != null) {
            keyboardController?.hide()
            onSaved(savedProductId)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (productId == null) {
                                R.string.new_product_title
                            } else {
                                R.string.edit_product_title
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onBack()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
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

            uiState.formError == EditorFormError.PRODUCT_NOT_FOUND -> ProductNotFoundEditor(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onBack = onBack,
            )

            else -> ProductForm(
                uiState = uiState,
                onNameChange = viewModel::onNameChange,
                onPriceChange = viewModel::onPriceChange,
                onBarcodeChange = viewModel::onBarcodeChange,
                onSave = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    viewModel.save()
                },
                onCancel = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onBack()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
            )
        }
    }
}

@Composable
private fun ProductForm(
    uiState: ProductEditorUiState,
    onNameChange: (String) -> Unit,
    onPriceChange: (String) -> Unit,
    onBarcodeChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val priceDigits = uiState.price.filterNot { it.isWhitespace() || it == '\u00A0' }
    val pricePreview = priceDigits.toLongOrNull()?.takeIf { uiState.priceError == null }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (uiState.formError == EditorFormError.SAVE_FAILED) {
            Text(
                text = stringResource(R.string.error_save_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        OutlinedTextField(
            value = uiState.name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.name_label)) },
            placeholder = { Text(stringResource(R.string.name_placeholder)) },
            isError = uiState.nameError != null,
            supportingText = uiState.nameError?.let { error ->
                { Text(stringResource(error.messageRes())) }
            },
            singleLine = true,
            enabled = !uiState.isSaving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        OutlinedTextField(
            value = uiState.price,
            onValueChange = onPriceChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.price_label)) },
            placeholder = { Text(stringResource(R.string.price_placeholder)) },
            isError = uiState.priceError != null,
            supportingText = {
                when {
                    uiState.priceError != null -> Text(
                        stringResource(uiState.priceError.messageRes()),
                    )

                    pricePreview != null -> Text(
                        stringResource(R.string.price_preview, formatKzt(pricePreview)),
                    )

                    else -> Text(stringResource(R.string.price_placeholder))
                }
            },
            singleLine = true,
            enabled = !uiState.isSaving,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
        )

        OutlinedTextField(
            value = uiState.barcode,
            onValueChange = onBarcodeChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.barcode_label)) },
            isError = uiState.barcodeError != null,
            supportingText = {
                Text(
                    text = uiState.barcodeError?.let { stringResource(it.messageRes()) }
                        ?: stringResource(R.string.barcode_hint),
                )
            },
            singleLine = true,
            enabled = !uiState.isSaving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
        )

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !uiState.isSaving && !uiState.saved,
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    text = stringResource(R.string.saving),
                    modifier = Modifier.padding(start = 10.dp),
                )
            } else {
                Text(stringResource(R.string.save))
            }
        }

        TextButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isSaving,
        ) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun ProductNotFoundEditor(
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
