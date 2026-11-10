package com.storeprice.app.domain.model

/** Роль сотрудника в общем каталоге. */
enum class EmployeeRole {
    ADMIN,
    EMPLOYEE,
}

/**
 * Профиль сотрудника, полученный с сервера после входа.
 *
 * Номер телефона берётся из сессии Auth и может отсутствовать в старых записях.
 */
data class EmployeeProfile(
    val id: String,
    val displayName: String,
    val phone: String?,
    val role: EmployeeRole,
    val isActive: Boolean,
)
