package iterator.nucleus.account.feature.interest

import iterator.nucleus.account.AccountService
import iterator.nucleus.account.InternalAccountRole
import iterator.nucleus.account.feature.FeatureConstants
import iterator.nucleus.audit.AccountProcessingPipelineFinishedEvent
import iterator.nucleus.audit.AuditService
import iterator.nucleus.audit.InterestAccruedEvent
import iterator.nucleus.audit.NucleusAuditEventType
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
class InterestAccrualListener(
  val accountService: AccountService,
  val ledgerService: LedgerEntryService,
  val kafka: KafkaTemplate<String, Any>,
  val audit: AuditService,
) {
  @Transactional
  @KafkaListener(topics = [InterestFeatureTopics.ACCRUE_INTEREST])
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
  fun accrueInterest(msg: InterestAccrualMessage) =
    doAccrualAndForward(
      interestRate = msg.params.interestRate,
      accrualAddress = InterestFeatureAddresses.ACCRUED_INCOMING,
      msg = msg,
    ) { m ->
      if (shouldAccrueBonusInterest(m)) {
        kafka.send(InterestFeatureTopics.ACCRUE_BONUS_INTEREST, m)
      } else if (shouldApplyInterest(m)) {
        forwardToCoalesceInterest(m)
      } else {
        publishPipelineEndEvent(m, "ACCRUE_INTEREST")
      }
    }

  @Transactional
  @KafkaListener(topics = [InterestFeatureTopics.ACCRUE_BONUS_INTEREST])
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
  fun accrueBonusInterest(msg: InterestAccrualMessage) =
    doAccrualAndForward(
      interestRate = msg.params.bonusInterestRate,
      accrualAddress = InterestFeatureAddresses.BONUS_ACCRUED_INCOMING,
      msg = msg,
      type = NucleusAuditEventType.BONUS_INTEREST_ACCRUED,
    ) { m ->
      if (shouldApplyInterest(m)) {
        forwardToCoalesceInterest(m)
      } else {
        publishPipelineEndEvent(m, "ACCRUE_BONUS_INTEREST")
      }
    }

  @Transactional
  @KafkaListener(topics = [InterestFeatureTopics.COALESCE_ACCRUED_INTEREST])
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
  fun coalesceAccruedInterest(msg: CoalesceAccruedInterestMessage) {
    val account = accountService.findRequiredOpenAccount(msg.accountId)
    ledgerService
      .findCommittedBalances(
        accountId = msg.accountId,
        effectiveTimestamp = msg.accrualTimestamp,
        addresses =
          setOf(
            InterestFeatureAddresses.ACCRUED_INCOMING,
            InterestFeatureAddresses.BONUS_ACCRUED_INCOMING,
          ),
        asset = LedgerConstants.DEFAULT_ASSET,
      ).filter { it.value > BigDecimal.ZERO }
      .forEach {
        ledgerService.createTransfer(
          fromAccount = account,
          fromAddress = it.key,
          toAccount = account,
          toAddress = InterestFeatureAddresses.TOTAL_ACCRUED_INCOMING,
          amount = it.value,
          timestamp = msg.accrualTimestamp,
        )
      }
    kafka.send(
      InterestFeatureTopics.APPLY_INTEREST,
      ApplyInterestMessage(
        accountId = msg.accountId,
        effectiveTimestamp = msg.effectiveTimestamp,
        accrualTimestamp = msg.accrualTimestamp,
        applicationTimestamp = msg.applicationTimestamp,
      ),
    )
  }

  private fun doAccrualAndForward(
    interestRate: BigDecimal,
    accrualAddress: String,
    msg: InterestAccrualMessage,
    type: NucleusAuditEventType = NucleusAuditEventType.INTEREST_ACCRUED,
    forward: (InterestAccrualMessage) -> Unit,
  ) {
    val accrued =
      msg.params.interestAccrualStrategy.calculateAccrual(
        balance = msg.balance,
        interestRate = interestRate,
        effectiveTimestamp = msg.effectiveTimestamp,
      )
    if (accrued > BigDecimal.ZERO) {
      val account = accountService.findRequiredOpenAccount(msg.accountId)
      val pnl = accountService.findRequiredInternalAccount(InternalAccountRole.PROFIT_AND_LOSS)
      ledgerService.createTransfer(
        fromAccount = pnl,
        fromAddress = InterestFeatureAddresses.ACCRUED_OUTGOING,
        toAccount = account,
        toAddress = accrualAddress,
        amount = accrued,
        timestamp = msg.effectiveTimestamp.plusMillis(1),
      )
      audit.publishAuditEvent(
        InterestAccruedEvent(
          accountId = msg.accountId,
          effectiveTimestamp = msg.effectiveTimestamp,
          effectiveBalance = msg.balance,
          effectiveInterestRate = interestRate,
          totalAccrued = accrued,
          accrualType = type,
        ),
      )
    }
    forward(msg)
  }

  private fun shouldApplyInterest(msg: InterestAccrualMessage): Boolean =
    msg.params.interestApplicationFrequency.shouldApplyInterest(
      params = msg.params,
      effectiveTimestamp = msg.effectiveTimestamp,
    )

  private fun shouldAccrueBonusInterest(msg: InterestAccrualMessage): Boolean =
    msg.balance > BigDecimal.ZERO && msg.params.bonusInterestEnabled

  private fun forwardToCoalesceInterest(msg: InterestAccrualMessage) {
    kafka.send(
      InterestFeatureTopics.COALESCE_ACCRUED_INTEREST,
      CoalesceAccruedInterestMessage(
        accountId = msg.accountId,
        effectiveTimestamp = msg.effectiveTimestamp,
        accrualTimestamp = msg.accrualTimestamp,
        applicationTimestamp = msg.applicationTimestamp,
        params = msg.params,
      ),
    )
  }

  private fun publishPipelineEndEvent(
    msg: InterestAccrualMessage,
    step: String,
  ) {
    audit.publishAuditEvent(
      AccountProcessingPipelineFinishedEvent(
        processingPipelineName = FeatureConstants.INTEREST_FEATURE_NAME,
        processingPipelineEndStep = step,
        accountId = msg.accountId,
        effectiveTimestamp = msg.effectiveTimestamp,
      ),
    )
  }
}
