package iterator.nucleus.account.feature.interest

import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.aValidInternalAccount
import iterator.nucleus.TestingFu.randomBigDecimal
import iterator.nucleus.TestingFu.randomInterestFeatureParameters
import iterator.nucleus.TestingFu.randomLong
import iterator.nucleus.account.Account
import iterator.nucleus.account.AccountService
import iterator.nucleus.account.InternalAccountRole
import iterator.nucleus.account.feature.FeatureConstants
import iterator.nucleus.audit.AccountProcessingPipelineFinishedEvent
import iterator.nucleus.audit.AuditService
import iterator.nucleus.audit.InterestAccruedEvent
import iterator.nucleus.audit.NucleusAuditEventType
import iterator.nucleus.ledger.LedgerEntryService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.eq
import org.mockito.kotlin.given
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

@ExtendWith(MockitoExtension::class)
class InterestAccrualListenerTest(
  @Mock val accountService: AccountService,
  @Mock val ledgerService: LedgerEntryService,
  @Mock val kafka: KafkaTemplate<String, Any>,
  @Mock val audit: AuditService,
) {
  val listener = InterestAccrualListener(accountService, ledgerService, kafka, audit)

  lateinit var now: Instant

  lateinit var accrualTimestamp: Instant

  lateinit var applicationTimestamp: Instant

  lateinit var today: ZonedDateTime

  lateinit var yesterday: ZonedDateTime

  lateinit var account: Account

  lateinit var pnl: Account

  lateinit var params: InterestFeatureParameters

  @BeforeEach
  fun setup() {
    now = Instant.now()
    accrualTimestamp = now.plusMillis(1)
    applicationTimestamp = now.plusMillis(2000)
    today = now.atZone(ZoneOffset.UTC)
    yesterday = now.atZone(ZoneOffset.UTC).minusDays(1)
    account = aValidAccount(aValidAccountTemplate()).apply { id = randomLong() }
    pnl =
      aValidInternalAccount(role = InternalAccountRole.PROFIT_AND_LOSS).apply {
        id = randomLong()
      }
    given { accountService.findRequiredOpenAccount(eq(account.accountId)) }.willReturn(account)
    given { accountService.findRequiredInternalAccount(eq(InternalAccountRole.PROFIT_AND_LOSS)) }
      .willReturn(pnl)
    params = randomInterestFeatureParameters()
  }

  @Test
  fun `should do nothing given zero interest accrued and not interest application day when accrue interest`() {
    // given
    val msg =
      InterestAccrualMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
        params =
          params.copy(
            interestApplicationFrequency = InterestApplicationFrequency.MONTHLY,
            interestApplicationDay = yesterday.dayOfMonth,
          ),
        balance = BigDecimal.ZERO,
      )

    // when
    listener.accrueInterest(msg)

    // then
    verify(audit, times(1))
      .publishAuditEvent(
        eq(
          AccountProcessingPipelineFinishedEvent(
            processingPipelineName = FeatureConstants.INTEREST_FEATURE_NAME,
            processingPipelineEndStep = "ACCRUE_INTEREST",
            accountId = msg.accountId,
            effectiveTimestamp = msg.effectiveTimestamp,
          ),
        ),
      )
    verifyNoInteractions(ledgerService, kafka, accountService)
  }

  @Test
  fun `should accrue interest and then stop given bonus disabled and not interest application day when accrue interest`() {
    // given
    val msg =
      InterestAccrualMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
        params =
          params.copy(
            interestRate = randomBigDecimal(0.01, 0.10),
            bonusInterestRate = BigDecimal.ZERO,
            interestApplicationFrequency = InterestApplicationFrequency.MONTHLY,
            interestApplicationDay = yesterday.dayOfMonth,
            bonusInterestEnabled = false,
          ),
        balance = randomBigDecimal(100.00, 1000.00),
      )
    val expectedAccrual =
      msg.params.interestAccrualStrategy.calculateAccrual(
        balance = msg.balance,
        interestRate = msg.params.interestRate,
        effectiveTimestamp = msg.effectiveTimestamp,
      )

    // when
    listener.accrueInterest(msg)

    // then
    verify(ledgerService, times(1))
      .createTransfer(
        fromAccount = eq(pnl),
        fromAddress = eq(InterestFeatureAddresses.ACCRUED_OUTGOING),
        toAccount = eq(account),
        toAddress = eq(InterestFeatureAddresses.ACCRUED_INCOMING),
        amount = eq(expectedAccrual),
        timestamp = eq(accrualTimestamp),
      )
    verify(audit, times(1))
      .publishAuditEvent(
        eq(
          InterestAccruedEvent(
            accountId = msg.accountId,
            effectiveTimestamp = msg.effectiveTimestamp,
            effectiveBalance = msg.balance,
            effectiveInterestRate = msg.params.interestRate,
            totalAccrued = expectedAccrual,
            accrualType = NucleusAuditEventType.INTEREST_ACCRUED,
          ),
        ),
      )
    verify(audit, times(1))
      .publishAuditEvent(
        eq(
          AccountProcessingPipelineFinishedEvent(
            processingPipelineName = FeatureConstants.INTEREST_FEATURE_NAME,
            processingPipelineEndStep = "ACCRUE_INTEREST",
            accountId = msg.accountId,
            effectiveTimestamp = msg.effectiveTimestamp,
          ),
        ),
      )
    verifyNoInteractions(kafka)
  }

  @Test
  fun `should accrue interest and then forward given bonus enabled`() {
    // given
    val msg =
      InterestAccrualMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
        params =
          params.copy(
            interestRate = randomBigDecimal(0.01, 0.10),
            bonusInterestRate = BigDecimal.ZERO,
            interestApplicationFrequency = InterestApplicationFrequency.MONTHLY,
            interestApplicationDay = yesterday.dayOfMonth,
            bonusInterestEnabled = true,
          ),
        balance = randomBigDecimal(100.00, 1000.00),
      )
    val expectedAccrual =
      msg.params.interestAccrualStrategy.calculateAccrual(
        balance = msg.balance,
        interestRate = msg.params.interestRate,
        effectiveTimestamp = msg.effectiveTimestamp,
      )

    // when
    listener.accrueInterest(msg)

    // then
    verify(ledgerService, times(1))
      .createTransfer(
        fromAccount = eq(pnl),
        fromAddress = eq(InterestFeatureAddresses.ACCRUED_OUTGOING),
        toAccount = eq(account),
        toAddress = eq(InterestFeatureAddresses.ACCRUED_INCOMING),
        amount = eq(expectedAccrual),
        timestamp = eq(accrualTimestamp),
      )
    verify(kafka, times(1)).send(eq(InterestFeatureTopics.ACCRUE_BONUS_INTEREST), eq(msg))
  }

  @Test
  fun `should accrue interest and then forward given bonus disabled and is interest application day`() {
    // given
    val msg =
      InterestAccrualMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
        params =
          params.copy(
            interestRate = randomBigDecimal(0.01, 0.10),
            bonusInterestRate = BigDecimal.ZERO,
            interestApplicationFrequency = InterestApplicationFrequency.MONTHLY,
            interestApplicationDay = today.dayOfMonth,
            bonusInterestEnabled = false,
          ),
        balance = randomBigDecimal(100.00, 1000.00),
      )
    val expectedAccrual =
      msg.params.interestAccrualStrategy.calculateAccrual(
        balance = msg.balance,
        interestRate = msg.params.interestRate,
        effectiveTimestamp = msg.effectiveTimestamp,
      )

    // when
    listener.accrueInterest(msg)

    // then
    verify(ledgerService, times(1))
      .createTransfer(
        fromAccount = eq(pnl),
        fromAddress = eq(InterestFeatureAddresses.ACCRUED_OUTGOING),
        toAccount = eq(account),
        toAddress = eq(InterestFeatureAddresses.ACCRUED_INCOMING),
        amount = eq(expectedAccrual),
        timestamp = eq(accrualTimestamp),
      )
    verify(kafka, times(1))
      .send(
        eq(InterestFeatureTopics.COALESCE_ACCRUED_INTEREST),
        eq(
          CoalesceAccruedInterestMessage(
            accountId = account.accountId,
            effectiveTimestamp = now,
            params = msg.params,
            accrualTimestamp = msg.accrualTimestamp,
            applicationTimestamp = msg.applicationTimestamp,
          ),
        ),
      )
  }

  @Test
  fun `should do nothing given zero bonus interest accrued and and not interest application day when accrue bonus interest`() {
    // given
    val msg =
      InterestAccrualMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
        params =
          params.copy(
            interestApplicationFrequency = InterestApplicationFrequency.MONTHLY,
            interestApplicationDay = yesterday.dayOfMonth,
          ),
        balance = BigDecimal.ZERO,
      )

    // when
    listener.accrueBonusInterest(msg)

    // then
    verifyNoInteractions(ledgerService, kafka, accountService)
  }

  @Test
  fun `should accrue bonus interest and then stop given not interest application day when accrue bonus interest`() {
    // given
    val msg =
      InterestAccrualMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
        params =
          params.copy(
            interestRate = BigDecimal.ZERO,
            bonusInterestRate = randomBigDecimal(0.01, 0.10),
            interestApplicationFrequency = InterestApplicationFrequency.MONTHLY,
            interestApplicationDay = yesterday.dayOfMonth,
          ),
        balance = randomBigDecimal(100.00, 1000.00),
      )
    val expectedAccrual =
      msg.params.interestAccrualStrategy.calculateAccrual(
        balance = msg.balance,
        interestRate = msg.params.bonusInterestRate,
        effectiveTimestamp = msg.effectiveTimestamp,
      )

    // when
    listener.accrueBonusInterest(msg)

    // then
    verify(ledgerService, times(1))
      .createTransfer(
        fromAccount = eq(pnl),
        fromAddress = eq(InterestFeatureAddresses.ACCRUED_OUTGOING),
        toAccount = eq(account),
        toAddress = eq(InterestFeatureAddresses.BONUS_ACCRUED_INCOMING),
        amount = eq(expectedAccrual),
        timestamp = eq(accrualTimestamp),
      )
    verify(audit, times(1))
      .publishAuditEvent(
        eq(
          InterestAccruedEvent(
            accountId = msg.accountId,
            effectiveTimestamp = msg.effectiveTimestamp,
            effectiveBalance = msg.balance,
            effectiveInterestRate = msg.params.bonusInterestRate,
            totalAccrued = expectedAccrual,
            accrualType = NucleusAuditEventType.BONUS_INTEREST_ACCRUED,
          ),
        ),
      )
    verify(audit, times(1))
      .publishAuditEvent(
        eq(
          AccountProcessingPipelineFinishedEvent(
            processingPipelineName = FeatureConstants.INTEREST_FEATURE_NAME,
            processingPipelineEndStep = "ACCRUE_BONUS_INTEREST",
            accountId = msg.accountId,
            effectiveTimestamp = msg.effectiveTimestamp,
          ),
        ),
      )
    verifyNoInteractions(kafka)
  }

  @Test
  fun `should accrue bonus interest and then forward given is interest application day`() {
    // given
    val msg =
      InterestAccrualMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
        params =
          params.copy(
            interestRate = BigDecimal.ZERO,
            bonusInterestRate = randomBigDecimal(0.01, 0.10),
            interestApplicationFrequency = InterestApplicationFrequency.MONTHLY,
            interestApplicationDay = today.dayOfMonth,
          ),
        balance = randomBigDecimal(100.00, 1000.00),
      )
    val expectedAccrual =
      msg.params.interestAccrualStrategy.calculateAccrual(
        balance = msg.balance,
        interestRate = msg.params.bonusInterestRate,
        effectiveTimestamp = msg.effectiveTimestamp,
      )

    // when
    listener.accrueBonusInterest(msg)

    // then
    verify(ledgerService, times(1))
      .createTransfer(
        fromAccount = eq(pnl),
        fromAddress = eq(InterestFeatureAddresses.ACCRUED_OUTGOING),
        toAccount = eq(account),
        toAddress = eq(InterestFeatureAddresses.BONUS_ACCRUED_INCOMING),
        amount = eq(expectedAccrual),
        timestamp = eq(accrualTimestamp),
      )
    verify(kafka, times(1))
      .send(
        eq(InterestFeatureTopics.COALESCE_ACCRUED_INTEREST),
        eq(
          CoalesceAccruedInterestMessage(
            accountId = account.accountId,
            effectiveTimestamp = now,
            accrualTimestamp = msg.accrualTimestamp,
            applicationTimestamp = msg.applicationTimestamp,
            params = msg.params,
          ),
        ),
      )
  }

  @Test
  fun `should move accrued incoming to total accrued incoming and forward to apply when coalesce interest`() {
    // given
    val msg =
      CoalesceAccruedInterestMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
        params = params,
      )
    val balances =
      mapOf(
        InterestFeatureAddresses.ACCRUED_INCOMING to randomBigDecimal(1.00, 10.00),
        InterestFeatureAddresses.BONUS_ACCRUED_INCOMING to BigDecimal.ZERO,
      )
    given {
      ledgerService.findCommittedBalances(
        accountId = account.accountId,
        effectiveTimestamp = msg.accrualTimestamp,
        addresses =
          setOf(
            InterestFeatureAddresses.ACCRUED_INCOMING,
            InterestFeatureAddresses.BONUS_ACCRUED_INCOMING,
          ),
      )
    }.willReturn(balances)

    // when
    listener.coalesceAccruedInterest(msg)

    // then
    verify(ledgerService, times(1))
      .createTransfer(
        fromAccount = eq(account),
        fromAddress = eq(InterestFeatureAddresses.ACCRUED_INCOMING),
        toAccount = eq(account),
        toAddress = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        amount = eq(balances[InterestFeatureAddresses.ACCRUED_INCOMING]!!),
        timestamp = eq(msg.accrualTimestamp),
      )
    verify(kafka, times(1))
      .send(
        eq(InterestFeatureTopics.APPLY_INTEREST),
        eq(
          ApplyInterestMessage(
            accountId = account.accountId,
            effectiveTimestamp = msg.effectiveTimestamp,
            accrualTimestamp = msg.accrualTimestamp,
            applicationTimestamp = msg.applicationTimestamp,
          ),
        ),
      )
  }

  @Test
  fun `should move bonus accrued incoming to total accrued incoming and forward to apply when coalesce interest`() {
    // given
    val msg =
      CoalesceAccruedInterestMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
        params = params,
      )
    val balances =
      mapOf(
        InterestFeatureAddresses.ACCRUED_INCOMING to BigDecimal.ZERO,
        InterestFeatureAddresses.BONUS_ACCRUED_INCOMING to randomBigDecimal(1.00, 10.00),
      )
    given {
      ledgerService.findCommittedBalances(
        accountId = account.accountId,
        effectiveTimestamp = msg.accrualTimestamp,
        addresses =
          setOf(
            InterestFeatureAddresses.ACCRUED_INCOMING,
            InterestFeatureAddresses.BONUS_ACCRUED_INCOMING,
          ),
      )
    }.willReturn(balances)

    // when
    listener.coalesceAccruedInterest(msg)

    // then
    verify(ledgerService, times(1))
      .createTransfer(
        fromAccount = eq(account),
        fromAddress = eq(InterestFeatureAddresses.BONUS_ACCRUED_INCOMING),
        toAccount = eq(account),
        toAddress = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        amount = eq(balances[InterestFeatureAddresses.BONUS_ACCRUED_INCOMING]!!),
        timestamp = eq(msg.accrualTimestamp),
      )
    verify(kafka, times(1))
      .send(
        eq(InterestFeatureTopics.APPLY_INTEREST),
        eq(
          ApplyInterestMessage(
            accountId = account.accountId,
            effectiveTimestamp = msg.effectiveTimestamp,
            accrualTimestamp = msg.accrualTimestamp,
            applicationTimestamp = msg.applicationTimestamp,
          ),
        ),
      )
  }

  @Test
  fun `should move accrued incoming and bonus accrued incoming to total accrued incoming and forward to apply when coalesce interest`() {
    // given
    val msg =
      CoalesceAccruedInterestMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
        params = params,
      )
    val balances =
      mapOf(
        InterestFeatureAddresses.ACCRUED_INCOMING to randomBigDecimal(1.00, 10.00),
        InterestFeatureAddresses.BONUS_ACCRUED_INCOMING to randomBigDecimal(1.00, 10.00),
      )
    given {
      ledgerService.findCommittedBalances(
        accountId = account.accountId,
        effectiveTimestamp = msg.accrualTimestamp,
        addresses =
          setOf(
            InterestFeatureAddresses.ACCRUED_INCOMING,
            InterestFeatureAddresses.BONUS_ACCRUED_INCOMING,
          ),
      )
    }.willReturn(balances)

    // when
    listener.coalesceAccruedInterest(msg)

    // then
    verify(ledgerService, times(1))
      .createTransfer(
        fromAccount = eq(account),
        fromAddress = eq(InterestFeatureAddresses.ACCRUED_INCOMING),
        toAccount = eq(account),
        toAddress = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        amount = eq(balances[InterestFeatureAddresses.ACCRUED_INCOMING]!!),
        timestamp = eq(msg.accrualTimestamp),
      )
    verify(ledgerService, times(1))
      .createTransfer(
        fromAccount = eq(account),
        fromAddress = eq(InterestFeatureAddresses.BONUS_ACCRUED_INCOMING),
        toAccount = eq(account),
        toAddress = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        amount = eq(balances[InterestFeatureAddresses.BONUS_ACCRUED_INCOMING]!!),
        timestamp = eq(msg.accrualTimestamp),
      )
    verify(kafka, times(1))
      .send(
        eq(InterestFeatureTopics.APPLY_INTEREST),
        eq(
          ApplyInterestMessage(
            accountId = account.accountId,
            effectiveTimestamp = msg.effectiveTimestamp,
            applicationTimestamp = msg.applicationTimestamp,
            accrualTimestamp = msg.accrualTimestamp,
          ),
        ),
      )
  }
}
