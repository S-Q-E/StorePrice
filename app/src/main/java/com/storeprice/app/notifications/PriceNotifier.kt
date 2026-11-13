package com.storeprice.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.storeprice.app.MainActivity
import com.storeprice.app.R
import com.storeprice.app.core.formatKzt
import com.storeprice.app.core.formatTimestamp

/** Создаёт канал и показывает локальное уведомление об изменении цены. */
internal object PriceNotifier {

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            PRICE_CHANGES_CHANNEL_ID,
            context.getString(R.string.notification_channel_price),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_price_description)
        }
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, payload: PriceNotificationPayload) {
        if (!canPostNotifications(context)) return
        createChannel(context)

        val openProduct = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_PRODUCT_ID, payload.productId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            payload.eventId.hashCode(),
            openProduct,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = context.getString(
            R.string.notification_price_body,
            payload.productName,
            formatKzt(payload.oldPriceKzt),
            formatKzt(payload.newPriceKzt),
            payload.changedByName,
            formatTimestamp(payload.changedAtEpochMillis),
        )
        val notification = NotificationCompat.Builder(context, PRICE_CHANGES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_price_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context)
            .notify(
                "price-${payload.eventId}",
                payload.eventId.hashCode() and Int.MAX_VALUE,
                notification,
            )
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
