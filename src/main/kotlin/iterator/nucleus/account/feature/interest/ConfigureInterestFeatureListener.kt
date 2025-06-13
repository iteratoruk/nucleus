package iterator.nucleus.account.feature.interest

import iterator.nucleus.account.AccountService
import iterator.nucleus.parameter.ParameterValueService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.retry.annotation.Backoff
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ConfigureInterestFeatureListener(
  val accountService: AccountService,
  val parameterService: ParameterValueService,
  val kafka: KafkaTemplate<String, Any>,
) {
  @Transactional
  @KafkaListener(topics = [InterestFeatureTopics.CONFIGURE_INTEREST])
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
  fun configureInterestFeature(msg: ConfigureInterestFeatureMessage) {
    val account = accountService.findRequiredOpenAccount(msg.accountId)
    val params =
      parameterService.findAndBindEffectiveParameters(
        dataClass = InterestFeatureParameters::class,
        effectiveAt = msg.effectiveTimestamp,
        accountId = account.accountId,
        accountTemplateId = account.accountTemplate.accountTemplateId,
        customerTrancheId = account.customerTranche?.customerTrancheId,
      )
    kafka.send(
      InterestFeatureTopics.COMMITTED_BALANCE,
      GetCommittedBalanceMessage(
        accountId = account.accountId,
        effectiveTimestamp = msg.effectiveTimestamp,
        accrualTimestamp = msg.accrualTimestamp,
        applicationTimestamp = msg.applicationTimestamp,
        params = params,
      ),
    )
  }
}
