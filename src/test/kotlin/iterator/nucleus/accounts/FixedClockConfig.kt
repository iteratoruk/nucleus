package iterator.nucleus.accounts

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@TestConfiguration
class FixedClockConfig {
  companion object {
    val FIXED_INSTANT: Instant = Instant.parse("2026-05-04T10:30:00Z")
  }

  @Bean @Primary
  fun fixedClock(): Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
}
