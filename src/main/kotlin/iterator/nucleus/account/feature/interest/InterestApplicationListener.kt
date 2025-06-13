package iterator.nucleus.account.feature.interest

import iterator.nucleus.account.AccountService
import iterator.nucleus.account.InternalAccountRole
import iterator.nucleus.ledger.LedgerConstants
import iterator.nucleus.ledger.LedgerEntryService
import iterator.nucleus.toTwoDecimalPlaces
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.retry.annotation.Backoff
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Component
class InterestApplicationListener(
  val accountService: AccountService,
  val ledgerService: LedgerEntryService,
) {
  @Transactional
  @KafkaListener(topics = [InterestFeatureTopics.APPLY_INTEREST])
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
  fun applyInterest(msg: ApplyInterestMessage) {
    // 1. read total accrued incoming
    val totalAccrued =
      ledgerService.findCommittedBalance(
        accountId = msg.accountId,
        effectiveTimestamp = msg.accrualTimestamp,
        address = InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING,
        asset = LedgerConstants.DEFAULT_ASSET,
      )

    // 2. round to two decimals using HALF_EVEN
    val rounded = totalAccrued.toTwoDecimalPlaces()

    // 3. if nothing to apply, bail out
    if (rounded.compareTo(BigDecimal.ZERO) == 0) return

    val account = accountService.findRequiredOpenAccount(msg.accountId)
    val pnl = accountService.findRequiredInternalAccount(InternalAccountRole.PROFIT_AND_LOSS)

    // 4. transfer rounded amount from the accrual bucket to the customer account
    ledgerService.createTransfer(
      fromAccount = account,
      fromAddress = InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING,
      toAccount = account,
      toAddress = LedgerConstants.DEFAULT_ADDRESS,
      amount = rounded,
      timestamp = msg.applicationTimestamp,
    )

    // 5. compute and apply rounding difference via P&L bucket
    val diff = totalAccrued.subtract(rounded)
    if (diff.compareTo(BigDecimal.ZERO) != 0) {
      if (diff > BigDecimal.ZERO) {
        // return excess: debit customer, credit P&L outgoing
        ledgerService.createTransfer(
          fromAccount = account,
          fromAddress = InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING,
          toAccount = pnl,
          toAddress = InterestFeatureAddresses.ACCRUED_OUTGOING,
          amount = diff.abs(),
          timestamp = msg.applicationTimestamp,
        )
      } else {
        // top-up shortfall: debit P&L outgoing, credit customer
        ledgerService.createTransfer(
          fromAccount = pnl,
          fromAddress = InterestFeatureAddresses.ACCRUED_OUTGOING,
          toAccount = account,
          toAddress = InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING,
          amount = diff.abs(),
          timestamp = msg.applicationTimestamp,
        )
      }
    }
  }
}
