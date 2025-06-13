package iterator.nucleus.account.feature.interest

import iterator.nucleus.TestingFu.randomBigDecimal
import iterator.nucleus.TestingFu.randomInterestFeatureParameters
import iterator.nucleus.ledger.LedgerConstants
import iterator.nucleus.ledger.LedgerEntryService
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
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class HandleCommittedBalanceListenerTest(
  @Mock val ledger: LedgerEntryService,
  @Mock val kafka: KafkaTemplate<String, Any>,
) {
  val listener = HandleCommittedBalanceListener(ledger, kafka)

  @Test
  fun `should handle committed balance and send it to accrual handler`() {
    // given
    val msg =
      GetCommittedBalanceMessage(
        accountId = UUID.randomUUID(),
        effectiveTimestamp = Instant.now(),
        accrualTimestamp = Instant.now().plusMillis(500),
        applicationTimestamp = Instant.now().plusMillis(2000),
        params = randomInterestFeatureParameters(),
      )
    val balance = randomBigDecimal(100.00, 1000.00)
    given {
      ledger.findCommittedBalance(
        accountId = eq(msg.accountId),
        effectiveTimestamp = eq(msg.effectiveTimestamp),
        address = eq(LedgerConstants.DEFAULT_ADDRESS),
        asset = eq(LedgerConstants.DEFAULT_ASSET),
      )
    }.willReturn(balance)

    // when
    listener.handleCommittedBalance(msg)

    // then
    val expected =
      InterestAccrualMessage(
        accountId = msg.accountId,
        effectiveTimestamp = msg.effectiveTimestamp,
        params = msg.params,
        accrualTimestamp = msg.accrualTimestamp,
        applicationTimestamp = msg.applicationTimestamp,
        balance = balance,
      )
    verify(kafka, times(1)).send(eq(InterestFeatureTopics.ACCRUE_INTEREST), eq(expected))
  }

  @Test
  fun `should handle committed balance and send stop given zero balance`() {
    // given
    val msg =
      GetCommittedBalanceMessage(
        accountId = UUID.randomUUID(),
        effectiveTimestamp = Instant.now(),
        params = randomInterestFeatureParameters(),
        accrualTimestamp = Instant.now().plusMillis(500),
        applicationTimestamp = Instant.now().plusMillis(2000),
      )
    val balance = BigDecimal.ZERO
    given {
      ledger.findCommittedBalance(
        accountId = eq(msg.accountId),
        effectiveTimestamp = eq(msg.effectiveTimestamp),
        address = eq(LedgerConstants.DEFAULT_ADDRESS),
        asset = eq(LedgerConstants.DEFAULT_ASSET),
      )
    }.willReturn(balance)

    // when
    listener.handleCommittedBalance(msg)

    // then
    verifyNoInteractions(kafka)
  }

  @Test
  fun `should handle committed balance and send stop given negative balance`() {
    // given
    val msg =
      GetCommittedBalanceMessage(
        accountId = UUID.randomUUID(),
        effectiveTimestamp = Instant.now(),
        params = randomInterestFeatureParameters(),
        accrualTimestamp = Instant.now().plusMillis(500),
        applicationTimestamp = Instant.now().plusMillis(2000),
      )
    val balance = "-0.01".toBigDecimal()
    given {
      ledger.findCommittedBalance(
        accountId = eq(msg.accountId),
        effectiveTimestamp = eq(msg.effectiveTimestamp),
        address = eq(LedgerConstants.DEFAULT_ADDRESS),
        asset = eq(LedgerConstants.DEFAULT_ASSET),
      )
    }.willReturn(balance)

    // when
    listener.handleCommittedBalance(msg)

    // then
    verifyNoInteractions(kafka)
  }
}
