package iterator.nucleus

import java.time.Instant
import java.time.temporal.ChronoUnit

fun Instant.truncatedToPostgresAccuracy(): Instant = truncatedTo(ChronoUnit.MICROS)
