package com.storeprice.app.notifications

import java.time.OffsetDateTime

/** Дополнительные данные, которые FCM передаёт в MainActivity при нажатии. */
const val EXTRA_PRODUCT_ID = "com.storeprice.app.extra.PRODUCT_ID"

/**
 * Ключ `product_id` в data-части FCM. При системном показе уведомления
 * Firebase добавляет data-поля в intent запуска как есть, поэтому MainActivity
 * должна принимать и этот ключ, и собственный [EXTRA_PRODUCT_ID].
 */
const val FCM_PRODUCT_ID = "product_id"

const val PRICE_CHANGES_CHANNEL_ID = "price_changes"

/** Данные push-сообщения об изменении цены. */
internal data class PriceNotificationPayload(
    val eventId: String,
    val productId: String,
    val productName: String,
    val oldPriceKzt: Long,
    val newPriceKzt: Long,
    val changedByName: String,
    val changedAtEpochMillis: Long,
) {
    companion object {
        /**
         * Разбирает data-часть FCM. Возвращает `null`, если сообщение неполное
         * или не относится к изменению цены, чтобы не показывать ложное уведомление.
         */
        fun from(data: Map<String, String>): PriceNotificationPayload? {
            val eventId = data["event_id"]?.trim().orEmpty()
            val productId = data["product_id"]?.trim().orEmpty()
            val productName = data["product_name"]?.trim().orEmpty()
            val oldPrice = data["old_price_kzt"]?.toLongOrNull()
            val newPrice = data["new_price_kzt"]?.toLongOrNull()
            val changedBy = data["changed_by_name"]?.trim().orEmpty()
            val changedAt = data["changed_at"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { value ->
                    runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
                }

            if (eventId.isEmpty() || productId.isEmpty() || productName.isEmpty()) return null
            if (oldPrice == null || newPrice == null || oldPrice < 0 || newPrice < 0) return null
            if (changedBy.isEmpty() || changedAt == null) return null

            return PriceNotificationPayload(
                eventId = eventId,
                productId = productId,
                productName = productName,
                oldPriceKzt = oldPrice,
                newPriceKzt = newPrice,
                changedByName = changedBy,
                changedAtEpochMillis = changedAt,
            )
        }
    }
}
