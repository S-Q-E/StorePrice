package com.storeprice.app.data.notifications

import android.util.Log
import com.storeprice.app.data.remote.DeviceTokenRemote
import com.storeprice.app.domain.auth.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Регистрирует FCM-токен после входа и при его обновлении.
 *
 * Токен не регистрируется в офлайн-режиме и для неактивного профиля. Перед
 * выходом [unregister] отключает токен на сервере, чтобы предыдущий сотрудник
 * не получал уведомления на общем телефоне.
 */
internal class DeviceTokenRegistrar(
    private val remote: DeviceTokenRemote,
    private val tokenSource: FcmTokenSource,
    private val deviceId: String,
    private val authState: StateFlow<AuthState>,
    private val networkAvailable: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {

    private val registrationMutex = Mutex()

    @Volatile
    private var latestToken: String? = null

    fun start() {
        scope.launch {
            combine(authState, networkAvailable) { state, online ->
                val signedIn = state as? AuthState.SignedIn
                RegistrationWindow(
                    userId = signedIn?.employee?.id,
                    canRegister = signedIn != null &&
                        !signedIn.isOffline &&
                        online,
                )
            }
                .distinctUntilChanged()
                .collect { window ->
                    if (window.canRegister) register()
                }
        }
    }

    /** Вызывается из FirebaseMessagingService при ротации токена. */
    fun onNewToken(token: String) {
        latestToken = token
        scope.launch { register() }
    }

    suspend fun unregister() {
        registrationMutex.withLock {
            runCatching { remote.unregister(deviceId) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Could not deactivate the FCM token", error)
                }
        }
    }

    private suspend fun register() {
        val signedIn = authState.value as? AuthState.SignedIn ?: return
        if (signedIn.isOffline || !networkAvailable.value) return

        registrationMutex.withLock {
            // Повторно проверяем состояние под мьютексом: пока ждали токен,
            // пользователь мог выйти или уйти в офлайн.
            val current = authState.value as? AuthState.SignedIn ?: return
            if (current.isOffline || !networkAvailable.value) return

            val token = latestToken ?: tokenSource.getToken()?.also { latestToken = it } ?: return
            runCatching { remote.register(token, deviceId) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Could not register the FCM token", error)
                }
        }
    }

    private data class RegistrationWindow(
        val userId: String?,
        val canRegister: Boolean,
    )

    private companion object {
        const val TAG = "DeviceTokenRegistrar"
    }
}
