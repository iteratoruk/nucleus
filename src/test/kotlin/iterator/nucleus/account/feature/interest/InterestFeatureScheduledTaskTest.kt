package iterator.nucleus.account.feature.interest

import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.randomLong
import iterator.nucleus.TestingFu.validAccountsWithIds
import iterator.nucleus.account.AccountFeature
import iterator.nucleus.account.AccountFeatureRepository
import iterator.nucleus.account.feature.FeatureConstants
import iterator.nucleus.schedule.ScheduledTaskResult
import iterator.nucleus.schedule.ScheduledTaskStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.given
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class InterestFeatureScheduledTaskTest(
  @Mock val repo: AccountFeatureRepository,
  @Mock val kafka: KafkaTemplate<String, Any>,
) {
  val task = InterestFeatureScheduledTask(repo, kafka)

  @Test
  fun `should send messages with effective timestamp for accounts when run`() {
    // given
    val template = aValidAccountTemplate()
    val accounts = validAccountsWithIds(numberOfAccounts = 3, accountTemplate = template)
    val feature =
      AccountFeature(
        name = FeatureConstants.INTEREST_FEATURE_NAME,
        accounts = accounts.toMutableSet(),
      )
    given { repo.findByName(eq(FeatureConstants.INTEREST_FEATURE_NAME)) }.willReturn(feature)
    val effectiveTimestamp = Instant.now()
    val data =
      InterestFeatureScheduledTaskData(
        effectiveTimestamp = effectiveTimestamp,
        accrualTimestamp = effectiveTimestamp.plusMillis(randomLong(1, 500)),
        applicationTimestamp = effectiveTimestamp.plusMillis(randomLong(2000, 5000)),
        incrementDuration = Duration.ofDays(randomLong(1, 31)),
      )
    val expected =
      ScheduledTaskResult(
        status = ScheduledTaskStatus.SUCCESS,
        data =
          data.copy(
            effectiveTimestamp =
              effectiveTimestamp.plusMillis(data.incrementDuration.toMillis()),
            applicationTimestamp =
              data.applicationTimestamp.plusMillis(data.incrementDuration.toMillis()),
            accrualTimestamp =
              data.accrualTimestamp.plusMillis(data.incrementDuration.toMillis()),
          ),
      )
    val expectedMessages =
      feature.accounts.map {
        ConfigureInterestFeatureMessage(
          accountId = it.accountId,
          effectiveTimestamp = data.effectiveTimestamp,
          accrualTimestamp = data.accrualTimestamp,
          applicationTimestamp = data.applicationTimestamp,
        )
      }

    // when
    val actual = task.run(data)

    // then
    val captor = argumentCaptor<ConfigureInterestFeatureMessage>()
    assertThat(actual).isEqualTo(expected)
    verify(kafka, times(3)).send(eq(InterestFeatureTopics.CONFIGURE_INTEREST), captor.capture())
    val actualMessages = captor.allValues
    assertThat(actualMessages).hasSameElementsAs(expectedMessages)
  }
}
