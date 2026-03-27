package com.sphereon.portal.bridge.persistence

import app.cash.sqldelight.ColumnAdapter
import kotlinx.datetime.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * SQLDelight column adapter for `TIMESTAMP WITH TIME ZONE AS Instant`.
 * Converts between kotlinx-datetime Instant and Java OffsetDateTime
 * (the JDBC PostgreSQL driver's native type for timestamptz columns).
 */
object InstantColumnAdapter : ColumnAdapter<Instant, OffsetDateTime> {
    override fun decode(databaseValue: OffsetDateTime): Instant {
        val javaInstant = databaseValue.toInstant()
        return Instant.fromEpochSeconds(javaInstant.epochSecond, javaInstant.nano.toLong())
    }

    override fun encode(value: Instant): OffsetDateTime {
        val javaInstant = java.time.Instant.ofEpochSecond(value.epochSeconds, value.nanosecondsOfSecond.toLong())
        return OffsetDateTime.ofInstant(javaInstant, ZoneOffset.UTC)
    }
}
