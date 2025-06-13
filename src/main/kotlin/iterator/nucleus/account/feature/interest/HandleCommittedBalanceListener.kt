package iterator.nucleus.account.feature.interest

import iterator.nucleus.ledger.LedgerConstants
import iterator.nucleus.ledger.LedgerEntryService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.retry.annotation.Backoff
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Component
class HandleCommittedBalanceListener(
  val ledger: LedgerEntryService,
  val kafka: KafkaTemplate<String, Any>,
) {
  @Transactional
  @KafkaListener(topics = [InterestFeatureTopics.COMMITTED_BALANCE])
  @RetryableTopic(
    attempts = "\${nucleus.account.features.interest.kafka.retry.max-attempts}",
    backoff =
      Backoff(
        delayExpression = "\${nucleus.account.features.interest.kafka.retry.delay}",
        multiplierExpression = "\${nucleus.account.features.interest.kafka.retry.multiplier}",
        maxDelayExpression = "\${nucleus.account.features.interest.kafka.retry.max-delay}",
      ),
    exclude = [IllegalArgumentException::class],
  )
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
