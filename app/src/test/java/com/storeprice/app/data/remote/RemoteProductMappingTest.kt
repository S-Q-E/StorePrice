package com.storeprice.app.data.remote

import io.github.jan.supabase.exceptions.RestException
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

    @Test
    fun `duplicate barcode message wins over generic conflict status`() {
        val error = RestException(
            error = "Штрихкод уже используется активным товаром",
            description = null,
            statusCode = 409,
            message = "conflict",
        )

        assertEquals(ProductRemoteException.DuplicateBarcode, error.toProductRemoteException())
    }

    @Test
    fun `default PostgreSQL duplicate message is also a duplicate barcode`() {
        val error = RestException(
            error = "duplicate key value violates unique constraint \"products_active_barcode_uidx\"",
            description = "Key (lower(barcode))=(00123) already exists.",
            statusCode = 409,
            message = "conflict",
        )

        assertEquals(ProductRemoteException.DuplicateBarcode, error.toProductRemoteException())
    }

    @Test
    fun `version conflict message is not confused with duplicate barcode`() {
        val error = RestException(
            error = "Конфликт версий: товар изменён другим сотрудником",
            description = null,
            statusCode = 409,
            message = "conflict",
        )

        assertEquals(ProductRemoteException.VersionConflict, error.toProductRemoteException())
    }

    @Test
    fun `not found and authorization errors are mapped`() {
        assertEquals(
            ProductRemoteException.NotFound,
            RestException("Товар не найден", null, 404, "not found")
                .toProductRemoteException(),
        )
        assertEquals(
            ProductRemoteException.Unauthorized,
            RestException("Требуется вход", null, 401, "unauthorized")
                .toProductRemoteException(),
        )
        assertEquals(
            ProductRemoteException.Forbidden,
            RestException("Редактировать каталог может только администратор", null, 403, "forbidden")
                .toProductRemoteException(),
        )
    }

    @Test
    fun `unknown conflict is a server error`() {
        val error = RestException(
            error = "Неизвестная ошибка",
            description = null,
            statusCode = 409,
            message = "conflict",
        )

        assertEquals(ProductRemoteException.ServerError, error.toProductRemoteException())
    }
}
