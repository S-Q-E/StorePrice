package com.storeprice.app.data.remote

import android.util.Log
import com.storeprice.app.core.SearchText
import com.storeprice.app.data.local.ProductEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.time.OffsetDateTime
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A product row as stored on the Supabase server. */
internal data class RemoteProduct(
    val id: String,
    val barcode: String?,
    val name: String,
    val priceKzt: Long,
    val isDeleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Long,
) {
    fun toEntity(): ProductEntity {
        val displayName = SearchText.normalizeDisplay(name)
        val normalizedBarcode = barcode?.trim()?.takeIf { it.isNotEmpty() }
        return ProductEntity(
            id = id,
            barcode = normalizedBarcode,
            barcodeNormalized = normalizedBarcode?.lowercase(Locale.ROOT),
            name = displayName,
            nameNormalized = SearchText.normalize(displayName),
            priceKzt = priceKzt,
            isDeleted = isDeleted,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
        )
    }
}

/** Errors that the repository can map to UI-friendly save results. */
internal sealed class ProductRemoteException(message: String) : Exception(message) {
    data object DuplicateBarcode : ProductRemoteException("Barcode is already used")
    data object VersionConflict : ProductRemoteException("Product version conflict")
    data object NotFound : ProductRemoteException("Product not found")
    data object Unauthorized : ProductRemoteException("Session is not authorized")
    data object NetworkUnavailable : ProductRemoteException("Server is unreachable")
    data object ServerError : ProductRemoteException("Server error")
}

/** Network operations used by the catalog repository. */
internal interface ProductRemote {
    suspend fun fetchAll(): List<RemoteProduct>

    suspend fun create(
        name: String,
        priceKzt: Long,
        barcode: String?,
    ): RemoteProduct

    suspend fun update(
        id: String,
        expectedVersion: Long,
        name: String,
        priceKzt: Long,
        barcode: String?,
    ): RemoteProduct

    suspend fun softDelete(
        id: String,
        expectedVersion: Long,
    ): RemoteProduct
}

/**
 * Supabase/PostgREST implementation.
 *
 * Reads are paged so an initial catalog above the PostgREST row limit is loaded
 * completely. Writes go through the server RPCs, which enforce roles, version
 * checks, price history and audit logging.
 */
internal class SupabaseProductRemote(
    private val supabase: SupabaseClient,
) : ProductRemote {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override suspend fun fetchAll(): List<RemoteProduct> = execute {
        val result = mutableListOf<RemoteProduct>()
        var offset = 0L
        while (true) {
            val page = supabase.postgrest
                .from(TABLE_PRODUCTS)
                .select(Columns.ALL) {
                    order("id", Order.ASCENDING)
                    range(offset, offset + PAGE_SIZE - 1)
                }
                .let { decodeList(it) }

            result += page.map { it.toDomain() }
            if (page.size < PAGE_SIZE) break
            offset += page.size
        }
        result
    }

    override suspend fun create(
        name: String,
        priceKzt: Long,
        barcode: String?,
    ): RemoteProduct = execute {
        val result = supabase.postgrest.rpc(
            function = RPC_CREATE_PRODUCT,
            parameters = buildJsonObject {
                put("p_name", name)
                put("p_price_kzt", priceKzt)
                put("p_barcode", if (barcode != null) JsonPrimitive(barcode) else JsonNull)
            },
        )
        decodeSingle(result).toDomain()
    }

    override suspend fun update(
        id: String,
        expectedVersion: Long,
        name: String,
        priceKzt: Long,
        barcode: String?,
    ): RemoteProduct = execute {
        val result = supabase.postgrest.rpc(
            function = RPC_UPDATE_PRODUCT,
            parameters = buildJsonObject {
                put("p_id", id)
                put("p_expected_version", expectedVersion)
                put("p_name", name)
                put("p_price_kzt", priceKzt)
                put("p_barcode", if (barcode != null) JsonPrimitive(barcode) else JsonNull)
            },
        )
        decodeSingle(result).toDomain()
    }

    override suspend fun softDelete(
        id: String,
        expectedVersion: Long,
    ): RemoteProduct = execute {
        val result = supabase.postgrest.rpc(
            function = RPC_SOFT_DELETE_PRODUCT,
            parameters = buildJsonObject {
                put("p_id", id)
                put("p_expected_version", expectedVersion)
            },
        )
        decodeSingle(result).toDomain()
    }

    private fun decodeList(result: PostgrestResult): List<SupabaseProductRow> =
        json.decodeFromString(result.data)

    private fun decodeSingle(result: PostgrestResult): SupabaseProductRow {
        val element = json.parseToJsonElement(result.data)
        val objectElement = when (element) {
            is JsonObject -> element
            is JsonArray -> element.firstOrNull() as? JsonObject
            else -> null
        } ?: error("Unexpected RPC response")
        return json.decodeFromJsonElement(SupabaseProductRow.serializer(), objectElement)
    }

    private suspend fun <T> execute(block: suspend () -> T): T {
        return try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: RestException) {
            throw error.toRemoteException()
        } catch (error: HttpRequestTimeoutException) {
            Log.w(TAG, "Supabase product request timed out", error)
            throw ProductRemoteException.NetworkUnavailable
        } catch (error: HttpRequestException) {
            Log.w(TAG, "Supabase product request failed", error)
            throw ProductRemoteException.NetworkUnavailable
        } catch (error: ProductRemoteException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Unexpected Supabase product error", error)
            throw ProductRemoteException.ServerError
        }
    }

    private fun RestException.toRemoteException(): ProductRemoteException =
        toProductRemoteException()

    @Serializable
    private data class SupabaseProductRow(
        val id: String,
        val barcode: String? = null,
        val name: String,
        @SerialName("price_kzt") val priceKzt: Long,
        @SerialName("is_deleted") val isDeleted: Boolean,
        @SerialName("created_at") val createdAt: String,
        @SerialName("updated_at") val updatedAt: String,
        val version: Int,
    ) {
        fun toDomain(): RemoteProduct = RemoteProduct(
            id = id,
            barcode = barcode,
            name = name,
            priceKzt = priceKzt,
            isDeleted = isDeleted,
            createdAt = parseTimestamp(createdAt),
            updatedAt = parseTimestamp(updatedAt),
            version = version.toLong(),
        )

        private fun parseTimestamp(value: String): Long =
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }

    private companion object {
        const val TAG = "ProductRemote"
        const val TABLE_PRODUCTS = "products"
        const val RPC_CREATE_PRODUCT = "create_product"
        const val RPC_UPDATE_PRODUCT = "update_product"
        const val RPC_SOFT_DELETE_PRODUCT = "soft_delete_product"
        const val PAGE_SIZE = 1_000L
    }
}

/**
 * Maps PostgREST errors to the repository contract.
 *
 * supabase-kt 3.0.0 exposes the server message in [RestException.error] but
 * drops the SQLSTATE `code` from the response, so classification uses the
 * message, details, and HTTP status together.
 */
internal fun RestException.toProductRemoteException(): ProductRemoteException {
    val text = listOfNotNull(error, description).joinToString(" ").lowercase(Locale.ROOT)
    return when {
        statusCode == 401 || statusCode == 403 ->
            ProductRemoteException.Unauthorized

        text.contains("штрихкод уже используется") ||
            text.contains("duplicate key value") ||
            text.contains("дублирующее значение") ||
            text.contains("already exists") ||
            text.contains("уже существует") ->
            ProductRemoteException.DuplicateBarcode

        text.contains("конфликт версий") ||
            text.contains("serialization failure") ||
            text.contains("could not serialize") ->
            ProductRemoteException.VersionConflict

        text.contains("товар не найден") ||
            text.contains("no rows") ||
            statusCode == 404 ->
            ProductRemoteException.NotFound

        else -> ProductRemoteException.ServerError
    }
}
