package iterator.nucleus.account.feature.interest

import iterator.nucleus.account.feature.FeatureConstants
import iterator.nucleus.toSevenDecimalPlaces
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.Year
import java.time.ZoneOffset
import java.util.UUID

object InterestFeatureTopics {
  const val CONFIGURE_INTEREST =
    "${FeatureConstants.PRIVATE_FEATURE_TOPIC_PREFIX}interest.configure"
  const val COMMITTED_BALANCE = "${FeatureConstants.PRIVATE_FEATURE_TOPIC_PREFIX}interest.balance"
  const val ACCRUE_INTEREST = "${FeatureConstants.PRIVATE_FEATURE_TOPIC_PREFIX}interest.accrue"
  const val ACCRUE_BONUS_INTEREST =
    "${FeatureConstants.PRIVATE_FEATURE_TOPIC_PREFIX}interest.accrue.bonus"
  const val COALESCE_ACCRUED_INTEREST =
    "${FeatureConstants.PRIVATE_FEATURE_TOPIC_PREFIX}interest.accrue.coalesce"
  const val APPLY_INTEREST = "${FeatureConstants.PRIVATE_FEATURE_TOPIC_PREFIX}interest.apply"
}

object InterestFeatureAddresses {
  const val ACCRUED_OUTGOING = "INTEREST_ACCRUED_OUTGOING"
  const val ACCRUED_INCOMING = "INTEREST_ACCRUED_INCOMING"
  const val BONUS_ACCRUED_INCOMING = "BONUS_INTEREST_ACCRUED_INCOMING"
  const val TOTAL_ACCRUED_INCOMING = "TOTAL_ACCRUED_INCOMING"
}

data class ConfigureInterestFeatureMessage(
  val accountId: UUID,
  val effectiveTimestamp: Instant,
  val accrualTimestamp: Instant,
  val applicationTimestamp: Instant,
)

data class InterestFeatureParameters(
  val interestRate: BigDecimal,
  val bonusInterestEnabled: Boolean,
  val bonusInterestRate: BigDecimal,
  val interestAccrualStrategy: InterestAccrualStrategy,
  val interestApplicationFrequency: InterestApplicationFrequency,
  val interestApplicationDay: Int,
  val interestApplicationMonth: Int,
)

data class GetCommittedBalanceMessage(
  val accountId: UUID,
  val effectiveTimestamp: Instant,
  val accrualTimestamp: Instant,
  val applicationTimestamp: Instant,
  val params: InterestFeatureParameters,
)

data class InterestAccrualMessage(
  val accountId: UUID,
  val effectiveTimestamp: Instant,
  val accrualTimestamp: Instant,
  val applicationTimestamp: Instant,
  val params: InterestFeatureParameters,
  val balance: BigDecimal,
)

data class CoalesceAccruedInterestMessage(
  val accountId: UUID,
  val effectiveTimestamp: Instant,
  val accrualTimestamp: Instant,
  val applicationTimestamp: Instant,
  val params: InterestFeatureParameters,
)

data class ApplyInterestMessage(
  val accountId: UUID,
  val effectiveTimestamp: Instant,
  val accrualTimestamp: Instant,
  val applicationTimestamp: Instant,
)

enum class InterestAccrualStrategy {
  ACTUAL_ACTUAL {
    override fun getCompoundingFrequency(effectiveTimestamp: Instant): Int = Year.from(effectiveTimestamp.atZone(ZoneOffset.UTC)).length()
  },
  ACTUAL_365 {
    override fun getCompoundingFrequency(effectiveTimestamp: Instant): Int = 365
  },
  ;

  abstract fun getCompoundingFrequency(effectiveTimestamp: Instant): Int

  fun calculateAccrual(
    balance: BigDecimal,
    interestRate: BigDecimal,
    effectiveTimestamp: Instant,
  ): BigDecimal =
    balance
      .toSevenDecimalPlaces()
      .multiply(
        interestRate
          .toSevenDecimalPlaces()
          .divide(
            getCompoundingFrequency(effectiveTimestamp).toBigDecimal(),
            RoundingMode.HALF_EVEN,
          ).toSevenDecimalPlaces(),
        MathContext.UNLIMITED,
      ).toSevenDecimalPlaces()
}

enum class InterestApplicationFrequency {
  ANNUALLY {
    override fun shouldApplyInterest(
      params: InterestFeatureParameters,
      effectiveTimestamp: Instant,
    ): Boolean {
      // Convert Instant to UTC LocalDate fields
      val zdt = effectiveTimestamp.atZone(ZoneOffset.UTC)
      val monthOfYear = zdt.monthValue
      val dayOfMonth = zdt.dayOfMonth
      val lengthOfMonth = zdt.toLocalDate().lengthOfMonth()

      // Only consider this month if it matches the configured application month
      if (params.interestApplicationMonth != monthOfYear) {
        return false
      }

      // Case 1: Exact day‐match (e.g. config day 15 and today is 15)
      if (params.interestApplicationDay == dayOfMonth) {
        return true
      }

      // Case 2: "Apply on X" where X > lengthOfMonth (e.g. Feb 29 on non‐leap),
      // so if today is the last day of the month, apply.
      if (params.interestApplicationDay > lengthOfMonth && dayOfMonth == lengthOfMonth) {
        return true
      }

      return false
    }
  },
  MONTHLY {
    override fun shouldApplyInterest(
      params: InterestFeatureParameters,
      effectiveTimestamp: Instant,
    ): Boolean {
      // Convert Instant to UTC LocalDate fields
      val zdt = effectiveTimestamp.atZone(ZoneOffset.UTC)
      val dayOfMonth = zdt.dayOfMonth
      val lengthOfMonth = zdt.toLocalDate().lengthOfMonth()

      // Case 1: Exact day‐match (e.g. config day 15 and today is 15)
      if (params.interestApplicationDay == dayOfMonth) {
        return true
      }

      // Case 2: "Apply on X" where X > 28 and this is the last day of the month
      if (params.interestApplicationDay > 28 && dayOfMonth == lengthOfMonth) {
        return true
      }

      return false
    }
  },
  ;

  abstract fun shouldApplyInterest(
    params: InterestFeatureParameters,
    effectiveTimestamp: Instant,
  ): Boolean
}
