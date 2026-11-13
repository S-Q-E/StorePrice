package com.storeprice.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.storeprice.app.StorePriceApplication

/** Принимает FCM-токены и push-сообщения об изменении цены. */
class StorePriceMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        (application as? StorePriceApplication)?.container?.onFcmTokenRefreshed(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = PriceNotificationPayload.from(message.data) ?: return
        PriceNotifier.show(this, payload)
    }
}
