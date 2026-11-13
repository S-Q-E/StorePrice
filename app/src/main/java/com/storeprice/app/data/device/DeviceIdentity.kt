package com.storeprice.app.data.device

import android.content.Context
import java.util.UUID

/**
 * Стабильный идентификатор установки приложения.
 *
 * Он не является аппаратным идентификатором: значение генерируется приложением
 * и хранится только в локальных настройках. Идентификатор нужен, чтобы
 * исключить устройство автора изменения из push-рассылки и связать записи
 * истории цен с устройством.
 */
internal class DeviceIdentity(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(): String {
        val existing = preferences.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing

        return UUID.randomUUID().toString().also { generated ->
            preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "storeprice_device"
        const val KEY_DEVICE_ID = "device_id"
    }
}
