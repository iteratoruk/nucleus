package iterator.nucleus.account.feature.interest

import iterator.nucleus.kafka.TransactionalRetryingKafkaListener
import iterator.nucleus.ledger.LedgerConstants
import iterator.nucleus.ledger.LedgerEntryService
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class HandleCommittedBalanceListener(
  val ledger: LedgerEntryService,
  val kafka: KafkaTemplate<String, Any>,
) {
  @TransactionalRetryingKafkaListener(topics = [InterestFeatureTopics.COMMITTED_BALANCE])
  fun handleCommittedBalance(msg: GetCommittedBalanceMessage) {
    val balance =
      ledger.findCommittedBalance(
        accountId = msg.accountId,
        effectiveTimestamp = msg.effectiveTimestamp,
        address = LedgerConstants.DEFAULT_ADDRESS,
        asset = LedgerConstants.DEFAULT_ASSET,
      )
    if (balance > BigDecimal.ZERO) {
      kafka.send(
        InterestFeatureTopics.ACCRUE_INTEREST,
        InterestAccrualMessage(
          accountId = msg.accountId,
          effectiveTimestamp = msg.effectiveTimestamp,
          accrualTimestamp = msg.accrualTimestamp,
          applicationTimestamp = msg.applicationTimestamp,
          params = msg.params,
          balance = balance,
        ),
      )
    }
  }
}
