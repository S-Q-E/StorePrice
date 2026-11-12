package com.storeprice.app.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Emits once for every Realtime change in the shared products table.
 *
 * The payload is deliberately ignored: a row change is only a signal to run the
 * normal incremental Room sync. That keeps RLS, soft deletes and version checks
 * in the same path as a manual refresh, and avoids trusting partial payloads.
 *
 * The flow subscribes when collected and removes its channel when cancelled, so
 * the application can scope it to the foreground lifecycle.
 */
internal class ProductRealtimeSource(
    private val supabase: SupabaseClient,
) {

    fun changes(): Flow<Unit> = flow {
        val channel = supabase.channel(CHANNEL_ID)
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = SCHEMA) {
            table = TABLE_PRODUCTS
        }

        try {
            channel.subscribe()
            try {
                withTimeout(SUBSCRIBE_TIMEOUT_MILLIS) {
                    channel.status.first { it == RealtimeChannel.Status.SUBSCRIBED }
                }
            } catch (timeout: TimeoutCancellationException) {
                throw RealtimeSubscribeException(timeout)
            }

            changes.collect { emit(Unit) }
            // A normal completion means the channel stopped delivering events;
            // surface it so the application retry loop can resubscribe.
            throw RealtimeChannelClosedException()
        } finally {
            withContext(NonCancellable) {
                runCatching { supabase.realtime.removeChannel(channel) }
            }
        }
    }

    private class RealtimeSubscribeException(cause: Throwable) :
        IllegalStateException("Realtime channel did not subscribe", cause)

    private class RealtimeChannelClosedException :
        IllegalStateException("Realtime channel closed unexpectedly")

    private companion object {
        const val CHANNEL_ID = "products-catalog"
        const val SCHEMA = "public"
        const val TABLE_PRODUCTS = "products"
        const val SUBSCRIBE_TIMEOUT_MILLIS = 15_000L
    }
}
