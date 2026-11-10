package com.storeprice.app.data.auth

import android.content.Context
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Хранит access/refresh token в приватных SharedPreferences приложения.
 *
 * Пароль сюда не попадает. Файл доступен только процессу приложения, но не
 * шифруется; для усиления защиты можно заменить реализацию на EncryptedSharedPreferences.
 */
class SharedPreferencesSessionManager(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : SessionManager {

    private val preferences = context.applicationContext
        .getSharedPreferences(fileName, Context.MODE_PRIVATE)

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun saveSession(session: UserSession) {
        withContext(Dispatchers.IO) {
            preferences.edit()
                .putString(KEY_SESSION, json.encodeToString(session))
                .apply()
        }
    }

    override suspend fun loadSession(): UserSession? = withContext(Dispatchers.IO) {
        val stored = preferences.getString(KEY_SESSION, null) ?: return@withContext null
        runCatching { json.decodeFromString<UserSession>(stored) }
            .getOrElse {
                preferences.edit().remove(KEY_SESSION).apply()
                null
            }
    }

    override suspend fun deleteSession() {
        withContext(Dispatchers.IO) {
            preferences.edit().remove(KEY_SESSION).apply()
        }
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "storeprice_auth"
        const val KEY_SESSION = "session"
    }
}
