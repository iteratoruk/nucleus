package iterator.nucleus.account.feature.interest

import iterator.nucleus.TestingFu.randomBigDecimal
import iterator.nucleus.account.Account
import iterator.nucleus.account.AccountFeature
import iterator.nucleus.account.InternalAccountRole
import iterator.nucleus.account.feature.AbstractFeaturePipelineIntegrationTest
import iterator.nucleus.account.feature.FeatureConstants
import iterator.nucleus.account.template.AccountTemplate
import iterator.nucleus.audit.AccountProcessingPipelineFinishedEvent
import iterator.nucleus.audit.InterestAccruedEvent
import iterator.nucleus.audit.NucleusAuditEventType
import iterator.nucleus.parameter.ParameterDefinition
import iterator.nucleus.parameter.ParameterLevel
import iterator.nucleus.parameter.ParameterValue
import iterator.nucleus.schedule.ScheduledTaskStatus
import iterator.nucleus.toSevenDecimalPlaces
import iterator.nucleus.truncatedToPostgresAccuracy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.web.servlet.MockMvc
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class InterestFeaturePipelineTest
  @Autowired
  constructor(
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractFeaturePipelineIntegrationTest(ctx, mvc) {
    lateinit var interestFeature: AccountFeature

    @BeforeEach
    fun setupInterestFeature() {
      accountFeatureRepo.deleteAll()
      interestFeature = `create account feature`(FeatureConstants.INTEREST_FEATURE_NAME)
    }

    @Test
    @Disabled("test is blinking, despite best efforts to fix it -- need to investigate further")
    fun `should accrue interest for one account with positive balance when the pipeline runs`() {
      // given
      val strategy = InterestAccrualStrategy.ACTUAL_365
      val interestRate = "0.0500000".toBigDecimal()
      val balance = "1000.0000000".toBigDecimal()
      val effectiveTimestamp = Instant.now().truncatedToPostgresAccuracy()
      val accrualTimestamp = effectiveTimestamp.plusMillis(1000)
      val account = `create customer account`()
      `fund customer account`(
        account = account,
        fromAccount = internalAccounts[InternalAccountRole.WRITE_OFF]!!,
        balance = balance,
        timestamp = effectiveTimestamp,
      )
      `enable feature for account`(account, interestFeature)
      `setup interest feature parameters`(
        account = account,
        accountTemplate = accountTemplate,
        effectiveTimestamp = effectiveTimestamp,
        interestRate = interestRate,
        bonusEnabled = false,
        interestAccrualStrategy = strategy,
        interestApplicationFrequency = InterestApplicationFrequency.MONTHLY,
        interestApplicationDay = LocalDate.now().minusDays(1).dayOfMonth,
      )

      // when
      `when I trigger the scheduled task`(
        taskClass = InterestFeatureScheduledTask::class.java,
        data =
          InterestFeatureScheduledTaskData(
            effectiveTimestamp = effectiveTimestamp,
            accrualTimestamp = accrualTimestamp,
            applicationTimestamp = effectiveTimestamp.plusMillis(2000),
            incrementDuration = Duration.ofDays(1),
          ),
      )

      // then
      val expectedAccrual = strategy.calculateAccrual(balance, interestRate, effectiveTimestamp)
      `then an account-level audit event is produced`(
        expected =
          InterestAccruedEvent(
            accountId = account.accountId,
            effectiveTimestamp = effectiveTimestamp,
            effectiveBalance = balance,
            effectiveInterestRate = interestRate,
            totalAccrued = expectedAccrual,
            accrualType = NucleusAuditEventType.INTEREST_ACCRUED,
          ),
      )
      `then the scheduled task has finished with status`(
        InterestFeatureScheduledTask::class.java,
        ScheduledTaskStatus.SUCCESS,
      )
      `then an account-level audit event is produced`(
        expected =
          AccountProcessingPipelineFinishedEvent(
            processingPipelineName = FeatureConstants.INTEREST_FEATURE_NAME,
            processingPipelineEndStep = "ACCRUE_INTEREST",
            accountId = account.accountId,
            effectiveTimestamp = effectiveTimestamp,
          ),
      )
      `then account has balance for address and asset at timestamp`(
        accountId = account.accountId,
        timestamp = accrualTimestamp,
        balance = expectedAccrual,
        address = InterestFeatureAddresses.ACCRUED_INCOMING,
      )
    }

    fun `setup interest feature parameters`(
      account: Account,
      accountTemplate: AccountTemplate,
      effectiveTimestamp: Instant,
      interestRate: BigDecimal = randomBigDecimal(0.01, 0.10).toSevenDecimalPlaces(),
      bonusEnabled: Boolean = false,
      bonusInterestRate: BigDecimal = BigDecimal.ZERO,
      interestAccrualStrategy: InterestAccrualStrategy = InterestAccrualStrategy.ACTUAL_ACTUAL,
      interestApplicationFrequency: InterestApplicationFrequency =
        InterestApplicationFrequency.MONTHLY,
      interestApplicationDay: Int = 1,
      interestApplicationMonth: Int = 0,
    ): Map<ParameterDefinition, ParameterValue> {
      val interestRateParam = `create parameter definition`(name = "interestRate")
      val interestRateValue =
        `create parameter value`(
          definition = interestRateParam,
          value = interestRate.toPlainString(),
          level = ParameterLevel.ACCOUNT_TEMPLATE,
          resourceId = accountTemplate.accountTemplateId,
          effectiveFrom = effectiveTimestamp,
        )
      val bonusEnabledParam = `create parameter definition`(name = "bonusInterestEnabled")
      val bonusEnabledValue =
        `create parameter value`(
          definition = bonusEnabledParam,
          value = bonusEnabled.toString(),
          level = ParameterLevel.ACCOUNT,
          resourceId = account.accountId.toString(),
          effectiveFrom = effectiveTimestamp,
        )
      val bonusInterestRateParam = `create parameter definition`(name = "bonusInterestRate")
      val bonusInterestRateValue =
        `create parameter value`(
          definition = bonusInterestRateParam,
          value = bonusInterestRate.toPlainString(),
          level = ParameterLevel.ACCOUNT_TEMPLATE,
          resourceId = accountTemplate.accountTemplateId,
          effectiveFrom = effectiveTimestamp,
        )
      val interestAccrualStrategyParam =
        `create parameter definition`(name = "interestAccrualStrategy")
      val interestAccrualStrategyValue =
        `create parameter value`(
          definition = interestAccrualStrategyParam,
          value = interestAccrualStrategy.name,
          level = ParameterLevel.ACCOUNT_TEMPLATE,
          resourceId = accountTemplate.accountTemplateId,
          effectiveFrom = effectiveTimestamp,
        )
      val interestApplicationFrequencyParam =
        `create parameter definition`(name = "interestApplicationFrequency")
      val interestApplicationFrequencyValue =
        `create parameter value`(
          definition = interestApplicationFrequencyParam,
          value = interestApplicationFrequency.name,
          level = ParameterLevel.ACCOUNT_TEMPLATE,
          resourceId = accountTemplate.accountTemplateId,
          effectiveFrom = effectiveTimestamp,
        )
      val interestApplicationDayParam = `create parameter definition`(name = "interestApplicationDay")
      val interestApplicationDayValue =
        `create parameter value`(
          definition = interestApplicationDayParam,
          value = interestApplicationDay.toString(),
          level = ParameterLevel.ACCOUNT,
          resourceId = account.accountId.toString(),
          effectiveFrom = effectiveTimestamp,
        )
      val interestApplicationMonthParam =
        `create parameter definition`(name = "interestApplicationMonth")
      val interestApplicationMonthValue =
        `create parameter value`(
          definition = interestApplicationMonthParam,
          value = interestApplicationMonth.toString(),
          effectiveFrom = effectiveTimestamp,
        )
      return mapOf(
        interestRateParam to interestRateValue,
        bonusEnabledParam to bonusEnabledValue,
        bonusInterestRateParam to bonusInterestRateValue,
        interestAccrualStrategyParam to interestAccrualStrategyValue,
        interestApplicationFrequencyParam to interestApplicationFrequencyValue,
        interestApplicationDayParam to interestApplicationDayValue,
        interestApplicationMonthParam to interestApplicationMonthValue,
      )
    }
  }
