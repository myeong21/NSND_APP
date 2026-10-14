package com.jsm.nsnd.ui.common

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Old SQLite timestamps without offsets are UTC, just like new explicit UTC responses. */
object ServerTime {
    fun millis(value: String): Long? = runCatching {
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrElse {
            LocalDateTime.parse(value).toInstant(ZoneOffset.UTC)
        }.toEpochMilli()
    }.getOrNull()

    fun display(value: String, pattern: String = "yyyy.MM.dd HH:mm", zone: ZoneId = ZoneId.systemDefault()): String =
        millis(value)?.let { DateTimeFormatter.ofPattern(pattern).withZone(zone).format(Instant.ofEpochMilli(it)) }
            ?: value.replace("T", " ")

    fun utcOffsetMinutes(date: String, zone: ZoneId = ZoneId.systemDefault()): Int =
        LocalDate.parse(date).atStartOfDay(zone).offset.totalSeconds / 60
}
