package com.storeprice.app.ui.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storeprice.app.R
import com.storeprice.app.data.scanner.BarcodeAnalyzer
import com.storeprice.app.ui.rememberProductRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX scanner screen. Camera resources are bound to the screen lifecycle and released
 * when the composable leaves composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onExistingProduct: (String) -> Unit,
    onNewProduct: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScannerViewModel = viewModel(
        factory = ScannerViewModel.factory(rememberProductRepository()),
    ),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var hasCameraPermission by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        permissionRequested = true
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasCameraPermission = context.hasCameraPermission()
    }

    LaunchedEffect(hasCamera, hasCameraPermission, permissionRequested) {
        if (hasCamera && !hasCameraPermission && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(uiState.destination) {
        when (val destination = uiState.destination) {
            is ScannerDestination.ExistingProduct -> onExistingProduct(destination.productId)
            is ScannerDestination.NewProduct -> onNewProduct(destination.barcode)
            null -> Unit
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scanner_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.scanner_close),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when {
            !hasCamera -> ScannerMessage(
                title = stringResource(R.string.scanner_camera_unavailable_title),
                message = stringResource(R.string.scanner_camera_unavailable_message),
                modifier = contentModifier,
            ) {
                Button(onClick = onBack) {
                    Text(stringResource(R.string.scanner_back_to_catalog))
                }
            }

            !hasCameraPermission -> ScannerMessage(
                title = stringResource(R.string.scanner_permission_title),
                message = stringResource(R.string.scanner_permission_message),
                modifier = contentModifier,
            ) {
                Button(
                    onClick = {
                        permissionRequested = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                ) {
                    Text(stringResource(R.string.scanner_permission_grant))
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { context.openAppSettings() }) {
                    Text(stringResource(R.string.scanner_permission_settings))
                }
            }

            else -> CameraScannerContent(
                uiState = uiState,
                onBarcodeDetected = viewModel::onBarcodeDetected,
                onRetryLookup = viewModel::retryLookup,
                modifier = contentModifier,
            )
        }
    }
}

@Composable
private fun CameraScannerContent(
    uiState: ScannerUiState,
    onBarcodeDetected: (String) -> Unit,
    onRetryLookup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cameraRetryKey by rememberSaveable { mutableIntStateOf(0) }
    var cameraFailed by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier.background(Color.Black)) {
        if (cameraFailed) {
            ScannerMessage(
                title = stringResource(R.string.scanner_camera_error_title),
                message = stringResource(R.string.scanner_camera_error_message),
                modifier = Modifier.fillMaxSize(),
            ) {
                Button(
                    onClick = {
                        cameraFailed = false
                        cameraRetryKey += 1
                    },
                ) {
                    Text(stringResource(R.string.scanner_retry))
                }
            }
        } else {
            key(cameraRetryKey) {
                BarcodeCameraPreview(
                    onBarcodeDetected = onBarcodeDetected,
                    onCameraError = { cameraFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ScanningFrame()
        }

        if (uiState.isProcessing) {
            ProcessingOverlay()
        }

        if (uiState.lookupFailed) {
            LookupErrorOverlay(onRetry = onRetryLookup)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BarcodeCameraPreview(
    onBarcodeDetected: (String) -> Unit,
    onCameraError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBarcodeDetected by rememberUpdatedState(onBarcodeDetected)
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var provider: ProcessCameraProvider? = null
        var analysisExecutor: ExecutorService? = null
        var analyzer: BarcodeAnalyzer? = null
        var observedCameraState: LiveData<CameraState>? = null
        var cameraStateObserver: Observer<CameraState>? = null
        var disposed = false

        providerFuture.addListener(
            {
                if (disposed) return@addListener
                try {
                    val cameraProvider = providerFuture.get()
                    provider = cameraProvider
                    val cameraSelector = cameraProvider.availableCameraSelector()
                    val preview = Preview.Builder()
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }
                    val executor = Executors.newSingleThreadExecutor()
                    analysisExecutor = executor
                    val barcodeAnalyzer = BarcodeAnalyzer(
                        onBarcodeDetected = { value ->
                            currentOnBarcodeDetected(value)
                        },
                    )
                    analyzer = barcodeAnalyzer
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, barcodeAnalyzer) }

                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        analysis,
                    )
                    val cameraState = camera.cameraInfo.cameraState
                    val observer = Observer<CameraState> { state ->
                        if (
                            !disposed &&
                            state.type == CameraState.Type.CLOSED &&
                            state.error != null
                        ) {
                            currentOnCameraError()
                        }
                    }
                    observedCameraState = cameraState
                    cameraStateObserver = observer
                    cameraState.observe(lifecycleOwner, observer)
                } catch (_: Exception) {
                    provider?.unbindAll()
                    if (!disposed) currentOnCameraError()
                }
            },
            mainExecutor,
        )

        onDispose {
            disposed = true
            cameraStateObserver?.let { observer ->
                observedCameraState?.removeObserver(observer)
            }
            cameraStateObserver = null
            observedCameraState = null
            analyzer?.close()
            provider?.unbindAll()
            analysisExecutor?.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

@Composable
private fun ScanningFrame(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = stringResource(R.string.scanner_instruction),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .aspectRatio(1.55f)
                .border(
                    width = 2.dp,
                    color = Color.White,
                    shape = RoundedCornerShape(20.dp),
                ),
        )
    }
}

@Composable
private fun ProcessingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.8f),
            contentColor = Color.White,
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.scanner_processing))
            }
        }
    }
}

@Composable
private fun LookupErrorOverlay(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.scanner_lookup_error),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.scanner_retry))
            }
        }
    }
}

@Composable
private fun ScannerMessage(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_barcode_scan),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        actions()
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}

private fun ProcessCameraProvider.availableCameraSelector(): CameraSelector = when {
    hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
    hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
    else -> error("No camera available")
}
