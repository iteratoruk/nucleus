package iterator.nucleus.account.feature.interest

import iterator.nucleus.account.AccountService
import iterator.nucleus.kafka.TransactionalRetryingKafkaListener
import iterator.nucleus.parameter.ParameterValueService
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class ConfigureInterestFeatureListener(
  val accountService: AccountService,
  val parameterService: ParameterValueService,
  val kafka: KafkaTemplate<String, Any>,
) {
  @TransactionalRetryingKafkaListener(topics = [InterestFeatureTopics.CONFIGURE_INTEREST])
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
