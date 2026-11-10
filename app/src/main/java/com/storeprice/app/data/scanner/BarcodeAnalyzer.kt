package com.storeprice.app.data.scanner

import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable

/**
 * Analyzes CameraX frames locally with ML Kit and emits the first readable barcode value.
 *
 * Repeating the same value in consecutive frames is throttled, while the ViewModel still
 * locks the whole scan session after the first product lookup.
 */
class BarcodeAnalyzer(
    private val onBarcodeDetected: (String) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ImageAnalysis.Analyzer, Closeable {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_CODABAR,
            )
            .build(),
    )

    private val throttleLock = Any()
    private var lastValue: String? = null
    private var lastEmittedAt: Long = 0L

    @Volatile
    private var closed = false

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (closed) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = try {
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        } catch (_: IllegalArgumentException) {
            imageProxy.close()
            return
        }

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                if (closed) return@addOnSuccessListener
                val value = barcodes
                    .asSequence()
                    .mapNotNull { it.rawValue }
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                if (value != null && shouldEmit(value)) {
                    onBarcodeDetected(value)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun shouldEmit(value: String): Boolean = synchronized(throttleLock) {
        val now = clock()
        val isRepeated = value == lastValue && now - lastEmittedAt < REPEAT_THROTTLE_MS
        if (isRepeated) {
            false
        } else {
            lastValue = value
            lastEmittedAt = now
            true
        }
    }

    override fun close() {
        closed = true
        scanner.close()
    }

    private companion object {
        const val REPEAT_THROTTLE_MS = 1_500L
    }
}
