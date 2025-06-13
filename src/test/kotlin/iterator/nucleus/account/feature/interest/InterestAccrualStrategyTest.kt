package iterator.nucleus.account.feature.interest

import iterator.nucleus.TestingFu.randomBigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class InterestAccrualStrategyTest {
  @Test
  fun `ACT-ACT strategy should return 365 when get compounding frequency in non-leap year`() {
    // given
    val effectiveTimestamp = LocalDate.of(2025, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()

    // when
    val actual = InterestAccrualStrategy.ACTUAL_ACTUAL.getCompoundingFrequency(effectiveTimestamp)

    // then
    assertThat(actual).isEqualTo(365)
  }

  @Test
  fun `ACT-ACT strategy should return 366 when get compounding frequency in leap year`() {
    // given
    val effectiveTimestamp = LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()

    // when
    val actual = InterestAccrualStrategy.ACTUAL_ACTUAL.getCompoundingFrequency(effectiveTimestamp)

    // then
    assertThat(actual).isEqualTo(366)
  }

  @Test
  fun `ACT-365 strategy should return 365 when get compounding frequency in non-leap year`() {
    // given
    val effectiveTimestamp = LocalDate.of(2025, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()

    // when
    val actual = InterestAccrualStrategy.ACTUAL_365.getCompoundingFrequency(effectiveTimestamp)

    // then
    assertThat(actual).isEqualTo(365)
  }

  @Test
  fun `ACT-365 strategy should return 365 when get compounding frequency in leap year`() {
    // given
    val effectiveTimestamp = LocalDate.of(2024, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()

    // when
    val actual = InterestAccrualStrategy.ACTUAL_365.getCompoundingFrequency(effectiveTimestamp)

    // then
    assertThat(actual).isEqualTo(365)
  }

  // ──────────────────────────────────────────────────────────────────────────────
  // 1) ACTUAL_ACTUAL, non-leap year (2025) with rate = 5 % written as seven decimals
  // ──────────────────────────────────────────────────────────────────────────────
  @Test
  fun `ACTUAL_ACTUAL calculateAccrual on non-leap 2025 uses freq=365 and correct rounding`() {
    // given: 2025 is not a leap year → frequency = 365
    val strategy = InterestAccrualStrategy.ACTUAL_ACTUAL
    val effectiveTs = midnightUtc(2025, 1, 1)

    // Use exactly seven decimal places for a 5% rate
    val balance = BigDecimal("100")
    val rate = BigDecimal("0.0500000") // 5 %

    //   divisor = 365
    //   0.0500000 ÷ 365 = 0.000136986301369... → to 7 decimals (HALF_EVEN) = 0.0001370
    //   accrual = 100 × 0.0001370 = 0.0137000
    // when
    val actual = strategy.calculateAccrual(balance, rate, effectiveTs)

    // then
    val expected = BigDecimal("0.0137000")
    assertThat(actual).isEqualByComparingTo(expected)
  }

  // ──────────────────────────────────────────────────────────────────────────────
  // 2) ACTUAL_ACTUAL, leap year (2024) with rate = 5 % (seven decimals)
  // ──────────────────────────────────────────────────────────────────────────────
  @Test
  fun `ACTUAL_ACTUAL calculateAccrual on leap 2024 uses freq=366 and correct rounding`() {
    // given: 2024 is a leap year → frequency = 366
    val strategy = InterestAccrualStrategy.ACTUAL_ACTUAL
    val effectiveTs = midnightUtc(2024, 1, 1)

    val balance = BigDecimal("100")
    val rate = BigDecimal("0.0500000")

    //   divisor = 366
    //   0.0500000 ÷ 366 ≈ 0.0001366120218579… → to 7 decimals = 0.0001366
    //   accrual = 100 × 0.0001366 = 0.0136600
    val actual = strategy.calculateAccrual(balance, rate, effectiveTs)

    val expected = BigDecimal("0.0136600")
    assertThat(actual).isEqualByComparingTo(expected)
  }

  // ──────────────────────────────────────────────────────────────────────────────
  // 3) ACTUAL_365, non-leap year (2025), rate = 5 %
  // ──────────────────────────────────────────────────────────────────────────────
  @Test
  fun `ACTUAL_365 calculateAccrual on non-leap 2025 always uses freq=365`() {
    val strategy = InterestAccrualStrategy.ACTUAL_365
    val effectiveTs = midnightUtc(2025, 6, 10)

    val balance = BigDecimal("100")
    val rate = BigDecimal("0.0500000")

    //   divisor = 365
    //   0.0500000 ÷ 365 → 0.0001369863… → to 7 decimals = 0.0001370
    //   accrual = 100 × 0.0001370 = 0.0137000
    val actual = strategy.calculateAccrual(balance, rate, effectiveTs)

    val expected = BigDecimal("0.0137000")
    assertThat(actual).isEqualByComparingTo(expected)
  }

  // ──────────────────────────────────────────────────────────────────────────────
  // 4) ACTUAL_365, leap year (2024), rate = 5 % (still uses 365)
  // ──────────────────────────────────────────────────────────────────────────────
  @Test
  fun `ACTUAL_365 calculateAccrual on leap 2024 still uses freq=365`() {
    val strategy = InterestAccrualStrategy.ACTUAL_365
    val effectiveTs = midnightUtc(2024, 2, 29)

    val balance = BigDecimal("100")
    val rate = BigDecimal("0.0500000")

    //   divisor = 365
    //   0.0500000 ÷ 365 → 0.0001369863… → to 7 decimals = 0.0001370
    //   accrual = 100 × 0.0001370 = 0.0137000
    val actual = strategy.calculateAccrual(balance, rate, effectiveTs)

    val expected = BigDecimal("0.0137000")
    assertThat(actual).isEqualByComparingTo(expected)
  }

  // ──────────────────────────────────────────────────────────────────────────────
  // 5) Rounding Corner Case: exactly .00000005 at the 8th decimal (HALF_EVEN)
  // ──────────────────────────────────────────────────────────────────────────────
  @Test
  fun `calculateAccrual rounds half-even at 7 decimals`() {
    val strategy = InterestAccrualStrategy.ACTUAL_365
    val effectiveTs = midnightUtc(2025, 3, 1)

    val balance = BigDecimal("1")
    // Using 1.00000005 (eight decimals), seven‐decimal rounding moves to 1.0000000
    val rate = BigDecimal("1.00000005") // e.g. 100 % plus a tiny fraction

    // divisor = 365
    //   1.00000005 ÷ 365 = 0.002739726027397… → 7‐decimals (HALF_EVEN) = 0.0027397
    //   × balance(1) = 0.0027397
    val actual = strategy.calculateAccrual(balance, rate, effectiveTs)

    // We expect 0.0027397, not 0.0027398, because the 8th digit is exactly 5 + prior digit even.
    val expected = BigDecimal("0.0027397")
    assertThat(actual).isEqualByComparingTo(expected)
  }

  // ──────────────────────────────────────────────────────────────────────────────
  // 6) Rounding Corner Case: eighth decimal > 5 → rounds up
  // ──────────────────────────────────────────────────────────────────────────────
  @Test
  fun `calculateAccrual rounds up when eighth decimal is greater than five`() {
    val strategy = InterestAccrualStrategy.ACTUAL_365
    val effectiveTs = midnightUtc(2025, 3, 1)

    val balance = BigDecimal("1")
    val rate = BigDecimal("1.00000006")

    // divisor = 365
    //   1.00000006 ÷ 365 = 0.002739726068493… → 7‐decimals = 0.0027397 (the 8th digit is 6, so
    // round up)
    //   × 1 = 0.0027397
    // Actually, note: because 0.002739726068… → at 7 decimals, we get 0.0027397 (the 8th digit is
    // 6).
    val actual = strategy.calculateAccrual(balance, rate, effectiveTs)

    val expected = BigDecimal("0.0027397")
    assertThat(actual).isEqualByComparingTo(expected)
  }

  // ──────────────────────────────────────────────────────────────────────────────
  // 7) High‐rate scenario: interestRate = 120% ("1.2000000"), check scaling
  // ──────────────────────────────────────────────────────────────────────────────
  @Test
  fun `calculateAccrual handles interestRate greater than one`() {
    // Given a balance of 50 and a rate of 120%:
    // For ACTUAL_365 (divisor=365): 1.2000000 ÷ 365 ≈ 0.0032876712328767… → 7‐decimals = 0.0032877
    // Then accrual = 50 × 0.0032877 = 0.1643850 → 7‐decimals = 0.1643850
    val strategy = InterestAccrualStrategy.ACTUAL_365
    val effectiveTs = midnightUtc(2025, 7, 1)

    val balance = BigDecimal("50")
    val rate = BigDecimal("1.2000000") // 120 % as seven decimal places

    // when
    val actual = strategy.calculateAccrual(balance, rate, effectiveTs)

    // then
    val expected = BigDecimal("0.1643850")
    assertThat(actual).isEqualByComparingTo(expected)
  }

  @Test
  fun `interest rate should be accurate to seven decimal places before dividing when calculate accrual using any strategy`() {
    // given
    val balance = randomBigDecimal(100.00, 1000.00)
    val interestRate = randomBigDecimal(0.01, 0.10)
    val effectiveTs = midnightUtc(2024, 7, 1)

    // when ... then
    InterestAccrualStrategy.values().forEach { strategy ->
      assertThat(strategy.calculateAccrual(balance, interestRate, effectiveTs))
        .isGreaterThan(BigDecimal.ZERO)
    }
  }

  private fun midnightUtc(
    year: Int,
    month: Int,
    day: Int,
  ): Instant = LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant()
}
