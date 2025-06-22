package iterator.nucleus.account.feature.interest

import iterator.nucleus.TestingFu
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.stream.Stream

data class ApplyInterestScenario(
  val day: Int,
  val month: Int,
  val year: Int,
  val interestApplicationDay: Int,
  val interestApplicationMonth: Int = 0,
  val frequency: InterestApplicationFrequency,
  val shouldApply: Boolean,
) {
  override fun toString(): String =
    "should ${if (!shouldApply) "not" else ""} apply interest on day $day of month $month in $year " +
      "given application day $interestApplicationDay and month $interestApplicationMonth with frequency $frequency"
}

data class NextApplicationDateScenario(
  val startDate: LocalDate,
  val interestApplicationDay: Int,
  val interestApplicationMonth: Int = 0,
  val frequency: InterestApplicationFrequency,
  val expectedDate: LocalDate,
) {
  override fun toString(): String =
    "starting $startDate expecting $expectedDate for day $interestApplicationDay" +
      " month $interestApplicationMonth with frequency $frequency"
}

class InterestApplicationFrequencyTest {
  companion object {
    @JvmStatic
    fun shouldApplyInterestScenarios(): Stream<Arguments> =
      Stream.of(
        // ─── MONTHLY FREQUENCY ──────────────────────────────────────────────────────────
        // 1. Exact match on day (June 15)
        Arguments.of(
          ApplyInterestScenario(
            day = 15,
            month = 6,
            year = 2025,
            interestApplicationDay = 15,
            frequency = InterestApplicationFrequency.MONTHLY,
            shouldApply = true,
          ),
        ),
        // 2. Same month/day off by one (June 14 != June 15)
        Arguments.of(
          ApplyInterestScenario(
            day = 14,
            month = 6,
            year = 2025,
            interestApplicationDay = 15,
            frequency = InterestApplicationFrequency.MONTHLY,
            shouldApply = false,
          ),
        ),
        // 3. Last‐day‐of‐month case for a 31st rule in a 30‐day month (June 30 for day=31)
        Arguments.of(
          ApplyInterestScenario(
            day = 30,
            month = 6,
            year = 2025,
            interestApplicationDay = 31,
            frequency = InterestApplicationFrequency.MONTHLY,
            shouldApply = true,
          ),
        ),
        // 4. Not last day (June 29 ≠ “apply on 31”)
        Arguments.of(
          ApplyInterestScenario(
            day = 29,
            month = 6,
            year = 2025,
            interestApplicationDay = 31,
            frequency = InterestApplicationFrequency.MONTHLY,
            shouldApply = false,
          ),
        ),
        // 5. Last day of a 31-day month when rule is day=30 should NOT apply
        Arguments.of(
          ApplyInterestScenario(
            day = 31,
            month = 5,
            year = 2025,
            interestApplicationDay = 30,
            frequency = InterestApplicationFrequency.MONTHLY,
            shouldApply = false,
          ),
        ),
        // 6. Exact match on 28th in February (Feb 28, 2025 is normal match for day=28)
        Arguments.of(
          ApplyInterestScenario(
            day = 28,
            month = 2,
            year = 2025,
            interestApplicationDay = 28,
            frequency = InterestApplicationFrequency.MONTHLY,
            shouldApply = true,
          ),
        ),
        // 7. Last‐day‐of‐Feb(28) for an “apply on 29” rule in a non‐leap‐year (2025)
        Arguments.of(
          ApplyInterestScenario(
            day = 28,
            month = 2,
            year = 2025,
            interestApplicationDay = 29,
            frequency = InterestApplicationFrequency.MONTHLY,
            shouldApply = true,
          ),
        ),
        // 8. Not last day (Feb 27 < apply‐on 29) ⇒ should NOT apply
        Arguments.of(
          ApplyInterestScenario(
            day = 27,
            month = 2,
            year = 2025,
            interestApplicationDay = 29,
            frequency = InterestApplicationFrequency.MONTHLY,
            shouldApply = false,
          ),
        ),
        // 9. Leap‐year normal match (Feb 29, 2024 for day=29)
        Arguments.of(
          ApplyInterestScenario(
            day = 29,
            month = 2,
            year = 2024,
            interestApplicationDay = 29,
            frequency = InterestApplicationFrequency.MONTHLY,
            shouldApply = true,
          ),
        ),
        // 10. Leap‐year last‐day‐case for “apply on 30” rule (Feb 29, 2024 is last day,
        // interestApplicationDay=30)
        Arguments.of(
          ApplyInterestScenario(
            day = 29,
            month = 2,
            year = 2024,
            interestApplicationDay = 30,
            frequency = InterestApplicationFrequency.MONTHLY,
            shouldApply = true,
          ),
        ),
        // 11. Leap‐year not‐last (Feb 28, 2024 < apply‐on 30) ⇒ should NOT apply
        Arguments.of(
          ApplyInterestScenario(
            day = 28,
            month = 2,
            year = 2024,
            interestApplicationDay = 30,
            frequency = InterestApplicationFrequency.MONTHLY,
            shouldApply = false,
          ),
        ),
        // ─── ANNUAL FREQUENCY ───────────────────────────────────────────────────────────
        // 12. Exact annual match (June 15, 2025 for month=6, day=15)
        Arguments.of(
          ApplyInterestScenario(
            day = 15,
            month = 6,
            year = 2025,
            interestApplicationDay = 15,
            interestApplicationMonth = 6,
            frequency = InterestApplicationFrequency.ANNUALLY,
            shouldApply = true,
          ),
        ),
        // 13. Same month/day off by one (June 14, 2025 ≠ day=15)
        Arguments.of(
          ApplyInterestScenario(
            day = 14,
            month = 6,
            year = 2025,
            interestApplicationDay = 15,
            interestApplicationMonth = 6,
            frequency = InterestApplicationFrequency.ANNUALLY,
            shouldApply = false,
          ),
        ),
        // 14. Month mismatch (July 15, 2025 vs apply‐in June)
        Arguments.of(
          ApplyInterestScenario(
            day = 15,
            month = 7,
            year = 2025,
            interestApplicationDay = 15,
            interestApplicationMonth = 6,
            frequency = InterestApplicationFrequency.ANNUALLY,
            shouldApply = false,
          ),
        ),
        // 15. Non‐leap “Feb 28” with apply—“Feb 29” rule (Feb 28, 2025)
        Arguments.of(
          ApplyInterestScenario(
            day = 28,
            month = 2,
            year = 2025,
            interestApplicationDay = 29,
            interestApplicationMonth = 2,
            frequency = InterestApplicationFrequency.ANNUALLY,
            shouldApply = true,
          ),
        ),
        // 16. Leap‐year exact match (Feb 29, 2024 for apply‐in Feb day=29)
        Arguments.of(
          ApplyInterestScenario(
            day = 29,
            month = 2,
            year = 2024,
            interestApplicationDay = 29,
            interestApplicationMonth = 2,
            frequency = InterestApplicationFrequency.ANNUALLY,
            shouldApply = true,
          ),
        ),
        // 17. April 30 “last‐day‐of‐month” for apply‐on 31 rule in April
        Arguments.of(
          ApplyInterestScenario(
            day = 30,
            month = 4,
            year = 2025,
            interestApplicationDay = 31,
            interestApplicationMonth = 4,
            frequency = InterestApplicationFrequency.ANNUALLY,
            shouldApply = true,
          ),
        ),
        // 18. Same month but not‐last day (April 29, 2025 < apply‐on 31 in April)
        Arguments.of(
          ApplyInterestScenario(
            day = 29,
            month = 4,
            year = 2025,
            interestApplicationDay = 31,
            interestApplicationMonth = 4,
            frequency = InterestApplicationFrequency.ANNUALLY,
            shouldApply = false,
          ),
        ),
        // 19. January 31 “last‐day” for apply‐on 32 rule in January
        Arguments.of(
          ApplyInterestScenario(
            day = 31,
            month = 1,
            year = 2025,
            interestApplicationDay = 32,
            interestApplicationMonth = 1,
            frequency = InterestApplicationFrequency.ANNUALLY,
            shouldApply = true,
          ),
        ),
        // 20. Same month but not last (Jan 30, 2025 < apply‐on 32 in January)
        Arguments.of(
          ApplyInterestScenario(
            day = 30,
            month = 1,
            year = 2025,
            interestApplicationDay = 32,
            interestApplicationMonth = 1,
            frequency = InterestApplicationFrequency.ANNUALLY,
            shouldApply = false,
          ),
        ),
      )
  
  @JvmStatic
  fun nextApplicationDateScenarios(): Stream<Arguments> =
    Stream.of(
      // ─── MONTHLY FREQUENCY ────────────────────────────────────────────────
      Arguments.of(
        NextApplicationDateScenario(
          startDate = LocalDate.of(2025, 6, 14),
          interestApplicationDay = 15,
          frequency = InterestApplicationFrequency.MONTHLY,
          expectedDate = LocalDate.of(2025, 6, 15),
        ),
      ),
      Arguments.of(
        NextApplicationDateScenario(
          startDate = LocalDate.of(2025, 6, 15),
          interestApplicationDay = 15,
          frequency = InterestApplicationFrequency.MONTHLY,
          expectedDate = LocalDate.of(2025, 6, 15),
        ),
      ),
      Arguments.of(
        NextApplicationDateScenario(
          startDate = LocalDate.of(2025, 6, 16),
          interestApplicationDay = 15,
          frequency = InterestApplicationFrequency.MONTHLY,
          expectedDate = LocalDate.of(2025, 7, 15),
        ),
      ),
      Arguments.of(
        NextApplicationDateScenario(
          startDate = LocalDate.of(2025, 2, 27),
          interestApplicationDay = 29,
          frequency = InterestApplicationFrequency.MONTHLY,
          expectedDate = LocalDate.of(2025, 2, 28),
        ),
      ),
      Arguments.of(
        NextApplicationDateScenario(
          startDate = LocalDate.of(2024, 2, 28),
          interestApplicationDay = 30,
          frequency = InterestApplicationFrequency.MONTHLY,
          expectedDate = LocalDate.of(2024, 2, 29),
        ),
      ),
      Arguments.of(
        NextApplicationDateScenario(
          startDate = LocalDate.of(2025, 6, 30),
          interestApplicationDay = 31,
          frequency = InterestApplicationFrequency.MONTHLY,
          expectedDate = LocalDate.of(2025, 6, 30),
        ),
      ),
      // ─── ANNUAL FREQUENCY ─────────────────────────────────────────────────
      Arguments.of(
        NextApplicationDateScenario(
          startDate = LocalDate.of(2025, 1, 10),
          interestApplicationDay = 15,
          interestApplicationMonth = 2,
          frequency = InterestApplicationFrequency.ANNUALLY,
          expectedDate = LocalDate.of(2025, 2, 15),
        ),
      ),
      Arguments.of(
        NextApplicationDateScenario(
          startDate = LocalDate.of(2025, 2, 15),
          interestApplicationDay = 15,
          interestApplicationMonth = 2,
          frequency = InterestApplicationFrequency.ANNUALLY,
          expectedDate = LocalDate.of(2025, 2, 15),
        ),
      ),
      Arguments.of(
        NextApplicationDateScenario(
          startDate = LocalDate.of(2025, 3, 1),
          interestApplicationDay = 15,
          interestApplicationMonth = 2,
          frequency = InterestApplicationFrequency.ANNUALLY,
          expectedDate = LocalDate.of(2026, 2, 15),
        ),
      ),
      Arguments.of(
        NextApplicationDateScenario(
          startDate = LocalDate.of(2024, 2, 28),
          interestApplicationDay = 29,
          interestApplicationMonth = 2,
          frequency = InterestApplicationFrequency.ANNUALLY,
          expectedDate = LocalDate.of(2024, 2, 29),
        ),
      ),
      Arguments.of(
        NextApplicationDateScenario(
          startDate = LocalDate.of(2024, 3, 1),
          interestApplicationDay = 29,
          interestApplicationMonth = 2,
          frequency = InterestApplicationFrequency.ANNUALLY,
          expectedDate = LocalDate.of(2025, 2, 28),
        ),
      ),
      Arguments.of(
        NextApplicationDateScenario(
          startDate = LocalDate.of(2025, 5, 1),
          interestApplicationDay = 31,
          interestApplicationMonth = 4,
          frequency = InterestApplicationFrequency.ANNUALLY,
          expectedDate = LocalDate.of(2026, 4, 30),
        ),
      ),
    )
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("shouldApplyInterestScenarios")
  fun `should evaluate whether to apply interest correctly`(scenario: ApplyInterestScenario) {
    // given
    val effectiveTimestamp =
      LocalDate
        .of(scenario.year, scenario.month, scenario.day)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()

    val config =
      config(
        day = scenario.interestApplicationDay,
        month = scenario.interestApplicationMonth,
        frequency = scenario.frequency,
      )

    // when
    val actual = scenario.frequency.shouldApplyInterest(config, effectiveTimestamp)

    // then
    assertThat(actual).isEqualTo(scenario.shouldApply)
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nextApplicationDateScenarios")
  fun `should return next interest application date`(scenario: NextApplicationDateScenario) {
    val effectiveTimestamp = scenario.startDate.atStartOfDay(ZoneOffset.UTC).toInstant()
    val config =
      config(
        day = scenario.interestApplicationDay,
        month = scenario.interestApplicationMonth,
        frequency = scenario.frequency,
      )

    val nextDate = scenario.frequency.getNextInterestApplicationDate(config, effectiveTimestamp)

    assertThat(nextDate).isEqualTo(scenario.expectedDate)
    // ensure shouldApplyInterest is true on returned date
    assertThat(
        scenario.frequency.shouldApplyInterest(config, nextDate.atStartOfDay(ZoneOffset.UTC).toInstant())
    ).isTrue()

    var d = scenario.startDate
    while (d.isBefore(nextDate)) {
      val shouldApply = scenario.frequency.shouldApplyInterest(config, d.atStartOfDay(ZoneOffset.UTC).toInstant())
      assertThat(shouldApply).describedAs("should not apply on $d").isFalse()
      d = d.plusDays(1)
    }
  }

  private fun config(
    day: Int,
    month: Int = 0,
    frequency: InterestApplicationFrequency = InterestApplicationFrequency.MONTHLY,
  ): InterestFeatureParameters =
    InterestFeatureParameters(
      interestRate = TestingFu.randomBigDecimal(0.01, 1.00),
      bonusInterestEnabled = TestingFu.randomBoolean(),
      bonusInterestRate = TestingFu.randomBigDecimal(0.01, 1.00),
      interestAccrualStrategy = TestingFu.randomEnum(InterestAccrualStrategy::class.java),
      interestApplicationFrequency = frequency,
      interestApplicationDay = day,
      interestApplicationMonth = month,
    )
}
