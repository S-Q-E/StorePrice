package com.storeprice.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteProductMappingTest {

    @Test
    fun `server row is normalized for Room search and display`() {
        val entity = RemoteProduct(
            id = "server-1",
            barcode = " 00123 ",
            name = "  Молоко   3,2% ",
            priceKzt = 1_250,
            isDeleted = false,
            createdAt = 1_000,
            updatedAt = 2_000,
            version = 5,
        ).toEntity()

        assertEquals("server-1", entity.id)
        assertEquals("00123", entity.barcode)
        assertEquals("00123", entity.barcodeNormalized)
        assertEquals("Молоко 3,2%", entity.name)
        assertEquals("молоко 3,2%", entity.nameNormalized)
        assertEquals(1_250L, entity.priceKzt)
        assertEquals(2_000L, entity.updatedAt)
        assertEquals(5L, entity.version)
    }

    @Test
    fun `blank server barcode becomes null`() {
        val entity = RemoteProduct(
            id = "server-1",
            barcode = "   ",
            name = "Товар",
            priceKzt = 100,
            isDeleted = true,
            createdAt = 1_000,
            updatedAt = 1_000,
            version = 1,
        ).toEntity()

        assertNull(entity.barcode)
        assertNull(entity.barcodeNormalized)
    }
}
