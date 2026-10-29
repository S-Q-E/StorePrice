package com.storeprice.app.core

import java.util.Locale

/**
 * Normalization rules shared by storage and search so both sides compare equal values.
 */
object SearchText {

    /** Trims and collapses internal whitespace; keeps the original letter case. */
    fun normalizeDisplay(value: String): String =
        value.trim().replace(WHITESPACE, " ")

    /** Case-insensitive, whitespace-normalized form used for searching. */
    fun normalize(value: String): String =
        normalizeDisplay(value).lowercase(Locale.ROOT)

    /** Escapes user input for a SQL `LIKE ... ESCAPE '\'` expression. */
    fun escapeLike(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private val WHITESPACE = Regex("[\\s\\u00A0]+")
}
