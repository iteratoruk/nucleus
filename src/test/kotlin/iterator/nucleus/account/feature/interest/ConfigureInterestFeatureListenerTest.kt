package iterator.nucleus.account.feature.interest

import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.aValidCustomerTranche
import iterator.nucleus.TestingFu.randomInterestFeatureParameters
import iterator.nucleus.account.AccountService
import iterator.nucleus.parameter.ParameterValueService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.eq
import org.mockito.kotlin.given
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.kafka.core.KafkaTemplate
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ConfigureInterestFeatureListenerTest(
  @Mock val accountService: AccountService,
  @Mock val parameterService: ParameterValueService,
  @Mock val kafka: KafkaTemplate<String, Any>,
) {
  val listener = ConfigureInterestFeatureListener(accountService, parameterService, kafka)

  @Test
  fun `should find and bind effective parameters and send to accrual topic`() {
    // given
    val account =
      aValidAccount(
        accountTemplate = aValidAccountTemplate(),
        customerTranche = aValidCustomerTranche(),
      )
    val msg =
      ConfigureInterestFeatureMessage(
        accountId = account.accountId,
        effectiveTimestamp = Instant.now(),
        accrualTimestamp = Instant.now().plusMillis(500),
        applicationTimestamp = Instant.now().plusMillis(2000),
      )
    val params = randomInterestFeatureParameters()
    given { accountService.findRequiredOpenAccount(eq(account.accountId)) }.willReturn(account)
    given {
      parameterService.findAndBindEffectiveParameters(
        dataClass = eq(InterestFeatureParameters::class),
        effectiveAt = eq(msg.effectiveTimestamp),
        accountId = eq(account.accountId),
        accountTemplateId = eq(account.accountTemplate.accountTemplateId),
        customerTrancheId = eq(account.customerTranche!!.customerTrancheId),
      )
    }.willReturn(params)

    // when
    listener.configureInterestFeature(msg)

    // then
    val expected =
      GetCommittedBalanceMessage(
        accountId = account.accountId,
        effectiveTimestamp = msg.effectiveTimestamp,
        accrualTimestamp = msg.accrualTimestamp,
        applicationTimestamp = msg.applicationTimestamp,
        params = params,
      )
    verify(kafka, times(1)).send(eq(InterestFeatureTopics.COMMITTED_BALANCE), eq(expected))
  }
}
