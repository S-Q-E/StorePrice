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
import kotlin.math.hypot

/** Minimal barcode data needed to choose the code closest to the frame center. */
internal data class BarcodeCandidate(
    val rawValue: String?,
    val boundingBox: BarcodeBounds?,
)

/** Android-independent copy of a barcode bounding box. */
internal data class BarcodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Picks the readable barcode closest to the center of an upright frame.
 *
 * A missing bounding box has the lowest priority. If at least two different codes are
 * detected and every measured code center is farther than 35% of the frame width from
 * the image center, the frame is ignored because it is ambiguous.
 */
internal fun pickBarcode(
    barcodes: List<BarcodeCandidate>,
    width: Int,
    height: Int,
): BarcodeCandidate? {
    val candidates = barcodes.filter { !it.rawValue.isNullOrBlank() }
    if (candidates.isEmpty()) return null
    if (width <= 0 || height <= 0) {
        return candidates.firstOrNull { it.boundingBox != null } ?: candidates.first()
    }

    val imageCenterX = width / 2.0
    val imageCenterY = height / 2.0
    val maxCenterDistance = width * CENTER_DISTANCE_LIMIT

    val ranked = candidates.map { candidate ->
        val box = candidate.boundingBox
        RankedBarcode(
            candidate = candidate,
            distance = if (box == null) {
                null
            } else {
                hypot(box.centerX - imageCenterX, box.centerY - imageCenterY)
            },
        )
    }

    val nearestDistanceByValue = ranked
        .filter { it.distance != null }
        .groupBy { it.candidate.rawValue.orEmpty().trim() }
        .mapValues { (_, items) -> items.minOf { it.distance!! } }

    if (
        nearestDistanceByValue.size >= 2 &&
        nearestDistanceByValue.values.all { it > maxCenterDistance }
    ) {
        return null
    }

    return ranked.minWithOrNull(
        compareBy<RankedBarcode> { it.distance == null }
            .thenBy { it.distance ?: Double.MAX_VALUE },
    )?.candidate
}

private data class RankedBarcode(
    val candidate: BarcodeCandidate,
    val distance: Double?,
)

private const val CENTER_DISTANCE_LIMIT = 0.35

/**
 * Analyzes CameraX frames locally with ML Kit and emits the readable barcode closest to
 * the frame center.
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
                val rotation = imageProxy.imageInfo.rotationDegrees
                val rotatedWidth = if (rotation == 90 || rotation == 270) {
                    inputImage.height
                } else {
                    inputImage.width
                }
                val rotatedHeight = if (rotation == 90 || rotation == 270) {
                    inputImage.width
                } else {
                    inputImage.height
                }
                val selected = pickBarcode(
                    barcodes = barcodes.map { barcode ->
                        BarcodeCandidate(
                            rawValue = barcode.rawValue,
                            boundingBox = barcode.boundingBox?.let { box ->
                                BarcodeBounds(
                                    left = box.left,
                                    top = box.top,
                                    right = box.right,
                                    bottom = box.bottom,
                                )
                            },
                        )
                    },
                    width = rotatedWidth,
                    height = rotatedHeight,
                )
                val value = selected?.rawValue?.trim()?.takeIf { it.isNotEmpty() }
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
