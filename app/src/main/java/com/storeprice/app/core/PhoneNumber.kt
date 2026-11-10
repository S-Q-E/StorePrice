package com.storeprice.app.core

/**
 * Приводит введённый телефон к формату E.164, который ожидает Supabase Auth.
 *
 * Поддерживаются привычные форматы Казахстана и России: `8 700 123-45-67`,
 * `7 700 123 45 67`, `+7 (700) 123-45-67` и просто 10 цифр без кода страны.
 */
object PhoneNumber {

    private const val MIN_DIGITS = 8
    private const val MAX_DIGITS = 15

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val digits = trimmed.filter(Char::isDigit)
        if (digits.length !in MIN_DIGITS..MAX_DIGITS) return null

        if (trimmed.startsWith("+")) return "+$digits"

        return when {
            digits.length == 10 -> "+7$digits"
            digits.length == 11 && digits.startsWith("8") -> "+7${digits.drop(1)}"
            digits.length == 11 && digits.startsWith("7") -> "+$digits"
            else -> "+$digits"
        }
    }
}
