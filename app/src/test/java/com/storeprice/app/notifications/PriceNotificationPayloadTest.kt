package com.storeprice.app.notifications

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PriceNotificationPayloadTest {

    @Test
    fun `valid price change payload is parsed`() {
        val payload = PriceNotificationPayload.from(
            mapOf(
                "event_id" to "event-1",
                "product_id" to "product-1",
                "product_name" to "Кофе",
                "old_price_kzt" to "1200",
                "new_price_kzt" to "1350",
                "changed_by_name" to "Администратор",
                "changed_at" to "2026-11-13T07:55:42+00:00",
            ),
        )

        requireNotNull(payload)
        assertEquals("event-1", payload.eventId)
        assertEquals("product-1", payload.productId)
        assertEquals("Кофе", payload.productName)
        assertEquals(1_200L, payload.oldPriceKzt)
        assertEquals(1_350L, payload.newPriceKzt)
        assertEquals("Администратор", payload.changedByName)
        assertEquals(
            OffsetDateTime.parse("2026-11-13T07:55:42+00:00").toInstant().toEpochMilli(),
            payload.changedAtEpochMillis,
        )
    }

    @Test
    fun `payload without product id is ignored`() {
        val payload = PriceNotificationPayload.from(
            mapOf(
                "event_id" to "event-1",
                "product_name" to "Кофе",
                "old_price_kzt" to "1200",
                "new_price_kzt" to "1350",
                "changed_by_name" to "Администратор",
            ),
        )

        assertNull(payload)
    }

    @Test
    fun `payload without valid change time is ignored`() {
        val payload = PriceNotificationPayload.from(
            mapOf(
                "event_id" to "event-1",
                "product_id" to "product-1",
                "product_name" to "Кофе",
                "old_price_kzt" to "1200",
                "new_price_kzt" to "1350",
                "changed_by_name" to "Администратор",
                "changed_at" to "not-a-time",
            ),
        )

        assertNull(payload)
    }

    @Test
    fun `payload with invalid price is ignored`() {
        val payload = PriceNotificationPayload.from(
            mapOf(
                "event_id" to "event-1",
                "product_id" to "product-1",
                "product_name" to "Кофе",
                "old_price_kzt" to "-1",
                "new_price_kzt" to "1350",
                "changed_by_name" to "Администратор",
            ),
        )

        assertNull(payload)
    }
}
