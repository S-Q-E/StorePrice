package com.storeprice.app.data.notifications

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.storeprice.app.BuildConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Источник текущего FCM-токена; отсутствие Firebase не является ошибкой входа. */
internal fun interface FcmTokenSource {
    suspend fun getToken(): String?
}

internal class FirebaseFcmTokenSource(
    private val context: Context,
) : FcmTokenSource {

    override suspend fun getToken(): String? {
        if (!BuildConfig.FIREBASE_CONFIGURED) return null

        return try {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(task.result)
                    } else {
                        Log.w(TAG, "FCM token request failed", task.exception)
                        continuation.resume(null)
                    }
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Firebase is not configured for this build", error)
            null
        }
    }

    private companion object {
        const val TAG = "FcmTokenSource"
    }
}
