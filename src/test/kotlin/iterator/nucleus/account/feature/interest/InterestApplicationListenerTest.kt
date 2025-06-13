package iterator.nucleus.account.feature.interest

import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.aValidInternalAccount
import iterator.nucleus.TestingFu.randomInterestFeatureParameters
import iterator.nucleus.account.Account
import iterator.nucleus.account.AccountService
import iterator.nucleus.account.InternalAccountRole
import iterator.nucleus.ledger.LedgerConstants
import iterator.nucleus.ledger.LedgerEntryService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.given
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

@ExtendWith(MockitoExtension::class)
class InterestApplicationListenerTest(
  @Mock val accountService: AccountService,
  @Mock val ledgerService: LedgerEntryService,
) {
  val listener = InterestApplicationListener(accountService, ledgerService)

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
    account = aValidAccount(aValidAccountTemplate()).apply { id = 1 }
    pnl = aValidInternalAccount(role = InternalAccountRole.PROFIT_AND_LOSS).apply { id = 2 }
    given { accountService.findRequiredOpenAccount(eq(account.accountId)) }.willReturn(account)
    given { accountService.findRequiredInternalAccount(eq(InternalAccountRole.PROFIT_AND_LOSS)) }
      .willReturn(pnl)
    params = randomInterestFeatureParameters()
  }

  @Test
  fun `should do nothing given zero total interest accrued when apply interest`() {
    // given
    val msg =
      ApplyInterestMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
      )
    given {
      ledgerService.findCommittedBalance(
        accountId = eq(account.accountId),
        effectiveTimestamp = eq(accrualTimestamp),
        address = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        asset = eq(LedgerConstants.DEFAULT_ASSET),
      )
    }.willReturn(BigDecimal.ZERO)

    // when
    listener.applyInterest(msg)

    // then
    verify(ledgerService, times(0)).createTransfer(any(), any(), any(), any(), any(), any())
  }

  @Test
  fun `should apply interest exact two decimal places given total accrued 1,2300000`() {
    // given
    val msg =
      ApplyInterestMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
      )
    given {
      ledgerService.findCommittedBalance(
        accountId = eq(account.accountId),
        effectiveTimestamp = eq(accrualTimestamp),
        address = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        asset = eq(LedgerConstants.DEFAULT_ASSET),
      )
    }.willReturn("1.2300000".toBigDecimal())

    // when
    listener.applyInterest(msg)

    // then: customer gets 1.23 from the accrual bucket, timestamped correctly
    verify(ledgerService, times(1))
      .createTransfer(
        fromAccount = eq(account),
        fromAddress = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        toAccount = eq(account),
        toAddress = eq(LedgerConstants.DEFAULT_ADDRESS),
        amount = eq("1.23".toBigDecimal()),
        timestamp = eq(applicationTimestamp),
      )
    verify(ledgerService, times(1)).createTransfer(any(), any(), any(), any(), any(), any())
  }

  @Test
  fun `should apply interest rounding half-even down given total accrued 0,1250000`() {
    // given
    val msg =
      ApplyInterestMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
      )
    given {
      ledgerService.findCommittedBalance(
        accountId = eq(account.accountId),
        effectiveTimestamp = eq(accrualTimestamp),
        address = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        asset = eq(LedgerConstants.DEFAULT_ASSET),
      )
    }.willReturn("0.1250000".toBigDecimal())

    // when
    listener.applyInterest(msg)

    // then: 0.12 to customer, 0.005 returned to P&L, both timestamped correctly
    val order = inOrder(ledgerService)
    order
      .verify(ledgerService, times(1))
      .createTransfer(
        fromAccount = eq(account),
        fromAddress = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        toAccount = eq(account),
        toAddress = eq(LedgerConstants.DEFAULT_ADDRESS),
        amount = eq("0.12".toBigDecimal()),
        timestamp = eq(applicationTimestamp),
      )
    order
      .verify(ledgerService, times(1))
      .createTransfer(
        fromAccount = eq(account),
        fromAddress = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        toAccount = eq(pnl),
        toAddress = eq(InterestFeatureAddresses.ACCRUED_OUTGOING),
        amount = eq("0.0050000".toBigDecimal()),
        timestamp = eq(applicationTimestamp),
      )
    verify(ledgerService, times(2)).createTransfer(any(), any(), any(), any(), any(), any())
  }

  @Test
  fun `should apply interest rounding half-even up given total accrued 0,1350000`() {
    // given
    val msg =
      ApplyInterestMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
      )
    given {
      ledgerService.findCommittedBalance(
        accountId = eq(account.accountId),
        effectiveTimestamp = eq(accrualTimestamp),
        address = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        asset = eq(LedgerConstants.DEFAULT_ASSET),
      )
    }.willReturn("0.1350000".toBigDecimal())

    // when
    listener.applyInterest(msg)

    // then: 0.14 to customer, 0.005 top-up from P&L, both timestamped correctly
    val order = inOrder(ledgerService)
    order
      .verify(ledgerService)
      .createTransfer(
        fromAccount = eq(account),
        fromAddress = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        toAccount = eq(account),
        toAddress = eq(LedgerConstants.DEFAULT_ADDRESS),
        amount = eq("0.14".toBigDecimal()),
        timestamp = eq(applicationTimestamp),
      )
    order
      .verify(ledgerService)
      .createTransfer(
        fromAccount = eq(pnl),
        fromAddress = eq(InterestFeatureAddresses.ACCRUED_OUTGOING),
        toAccount = eq(account),
        toAddress = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        amount = eq("0.0050000".toBigDecimal()),
        timestamp = eq(applicationTimestamp),
      )
    verify(ledgerService, times(2)).createTransfer(any(), any(), any(), any(), any(), any())
  }

  @Test
  fun `should skip application when small accrual rounds to zero given total accrued 0,0012345`() {
    // given
    val msg =
      ApplyInterestMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
      )
    given {
      ledgerService.findCommittedBalance(
        accountId = eq(account.accountId),
        effectiveTimestamp = eq(accrualTimestamp),
        address = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        asset = eq(LedgerConstants.DEFAULT_ASSET),
      )
    }.willReturn("0.0012345".toBigDecimal())

    // when
    listener.applyInterest(msg)

    // then: no transfers at all
    verify(ledgerService, times(0)).createTransfer(any(), any(), any(), any(), any(), any())
  }

  @Test
  fun `should apply interest for large value with tiny diff given total accrued 1000,9999999`() {
    // given
    val msg =
      ApplyInterestMessage(
        accountId = account.accountId,
        effectiveTimestamp = now,
        accrualTimestamp = accrualTimestamp,
        applicationTimestamp = applicationTimestamp,
      )
    given {
      ledgerService.findCommittedBalance(
        accountId = eq(account.accountId),
        effectiveTimestamp = eq(accrualTimestamp),
        address = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        asset = eq(LedgerConstants.DEFAULT_ASSET),
      )
    }.willReturn("1000.9999999".toBigDecimal())

    // when
    listener.applyInterest(msg)

    // then: 1001.00 to customer, 0.0000001 top-up, both timestamped correctly
    val order = inOrder(ledgerService)
    order
      .verify(ledgerService)
      .createTransfer(
        fromAccount = eq(account),
        fromAddress = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        toAccount = eq(account),
        toAddress = eq(LedgerConstants.DEFAULT_ADDRESS),
        amount = eq("1001.00".toBigDecimal()),
        timestamp = eq(applicationTimestamp),
      )
    order
      .verify(ledgerService)
      .createTransfer(
        fromAccount = eq(pnl),
        fromAddress = eq(InterestFeatureAddresses.ACCRUED_OUTGOING),
        toAccount = eq(account),
        toAddress = eq(InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING),
        amount = eq("0.0000001".toBigDecimal()),
        timestamp = eq(applicationTimestamp),
      )
    verify(ledgerService, times(2)).createTransfer(any(), any(), any(), any(), any(), any())
  }
}
