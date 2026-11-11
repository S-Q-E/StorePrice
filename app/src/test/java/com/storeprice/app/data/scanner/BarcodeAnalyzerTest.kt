package com.storeprice.app.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodeAnalyzerTest {

    @Test
    fun `single barcode is selected even far from center`() {
        val barcode = barcode("4600000000018", left = 0, top = 0, right = 100, bottom = 100)

        assertEquals("4600000000018", pickBarcode(listOf(barcode), WIDTH, HEIGHT)?.rawValue)
    }

    @Test
    fun `closest barcode to frame center wins`() {
        val far = barcode("far", left = 0, top = 0, right = 100, bottom = 100)
        val near = barcode("near", left = 400, top = 400, right = 600, bottom = 600)

        assertEquals("near", pickBarcode(listOf(far, near), WIDTH, HEIGHT)?.rawValue)
    }

    @Test
    fun `barcode without bounding box has lowest priority`() {
        val withoutBox = BarcodeCandidate(rawValue = "without-box", boundingBox = null)
        val withBox = barcode("with-box", left = 0, top = 0, right = 100, bottom = 100)

        assertEquals(
            "with-box",
            pickBarcode(listOf(withoutBox, withBox), WIDTH, HEIGHT)?.rawValue,
        )
        assertEquals(
            "without-box",
            pickBarcode(listOf(withoutBox), WIDTH, HEIGHT)?.rawValue,
        )
    }

    @Test
    fun `empty list returns null`() {
        assertNull(pickBarcode(emptyList(), WIDTH, HEIGHT))
    }

    @Test
    fun `two different far barcodes ignore the frame`() {
        val left = barcode("left", left = 0, top = 0, right = 100, bottom = 100)
        val right = barcode("right", left = 900, top = 900, right = 1_000, bottom = 1_000)

        assertNull(pickBarcode(listOf(left, right), WIDTH, HEIGHT))
    }

    @Test
    fun `same far barcode detected twice is not ambiguous`() {
        val left = barcode("same", left = 0, top = 0, right = 100, bottom = 100)
        val right = barcode("same", left = 900, top = 900, right = 1_000, bottom = 1_000)

        assertEquals("same", pickBarcode(listOf(left, right), WIDTH, HEIGHT)?.rawValue)
    }

    private fun barcode(
        value: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): BarcodeCandidate = BarcodeCandidate(
        rawValue = value,
        boundingBox = BarcodeBounds(left, top, right, bottom),
    )

    private companion object {
        const val WIDTH = 1_000
        const val HEIGHT = 1_000
    }
}
