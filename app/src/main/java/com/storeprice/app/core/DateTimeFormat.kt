package com.storeprice.app.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val UPDATED_AT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"))

fun formatTimestamp(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(zoneId)
        .format(UPDATED_AT_FORMATTER)
