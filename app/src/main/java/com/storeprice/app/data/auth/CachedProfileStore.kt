package com.storeprice.app.data.auth

import android.content.Context
import com.storeprice.app.domain.model.EmployeeProfile
import com.storeprice.app.domain.model.EmployeeRole

/**
 * Профиль сотрудника, сохранённый для офлайн-доступа.
 *
 * Пароль, access token и refresh token здесь не хранятся. [cachedAtMillis] —
 * время последней успешной проверки профиля на сервере.
 */
data class CachedProfile(
    val id: String,
    val displayName: String,
    val role: EmployeeRole,
    val isActive: Boolean,
    val cachedAtMillis: Long,
) {
    /** Превращает кэш в профиль для UI. Телефон в кэше не хранится. */
    fun toEmployeeProfile(): EmployeeProfile = EmployeeProfile(
        id = id,
        displayName = displayName,
        phone = null,
        role = role,
        isActive = isActive,
    )
}

/**
 * Приватный кэш активного профиля в `SharedPreferences`.
 *
 * Нужен только для входа в каталог при временном отсутствии сети. Кэш
 * очищается при выходе, деактивации, отсутствии профиля и ответе 401.
 */
class CachedProfileStore(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val preferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Сохраняет профиль и время успешной проверки. */
    fun save(profile: EmployeeProfile) {
        preferences.edit()
            .putString(KEY_ID, profile.id)
            .putString(KEY_DISPLAY_NAME, profile.displayName)
            .putString(KEY_ROLE, profile.role.name)
            .putBoolean(KEY_IS_ACTIVE, true)
            .putLong(KEY_CACHED_AT, now())
            .apply()
    }

    /** Возвращает сохранённый профиль или `null`, если запись неполная. */
    fun load(): CachedProfile? {
        val id = preferences.getString(KEY_ID, null) ?: return null
        val displayName = preferences.getString(KEY_DISPLAY_NAME, null) ?: return null
        val role = preferences.getString(KEY_ROLE, null)
            ?.let { stored -> runCatching { EmployeeRole.valueOf(stored) }.getOrNull() }
            ?: return null
        val cachedAtMillis = preferences.getLong(KEY_CACHED_AT, 0L)
        if (cachedAtMillis <= 0L) return null

        return CachedProfile(
            id = id,
            displayName = displayName,
            role = role,
            isActive = preferences.getBoolean(KEY_IS_ACTIVE, true),
            cachedAtMillis = cachedAtMillis,
        )
    }

    /** Полностью удаляет сохранённый профиль. */
    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        const val FILE_NAME = "storeprice_profile_cache"

        private const val KEY_ID = "id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ROLE = "role"
        private const val KEY_IS_ACTIVE = "is_active"
        private const val KEY_CACHED_AT = "cached_at_millis"
    }
}
