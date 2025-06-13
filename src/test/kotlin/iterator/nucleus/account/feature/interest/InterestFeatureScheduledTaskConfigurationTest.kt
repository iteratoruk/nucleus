package iterator.nucleus.account.feature.interest

import iterator.nucleus.TestingFu.randomInterestFeatureConfigurationProperties
import iterator.nucleus.account.AccountFeatureRepository
import iterator.nucleus.schedule.ScheduledTaskDetails
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.kafka.core.KafkaTemplate
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@ExtendWith(MockitoExtension::class)
class InterestFeatureScheduledTaskConfigurationTest(
  @Mock val repo: AccountFeatureRepository,
  @Mock val kafka: KafkaTemplate<String, Any>,
) {
  val cfg = InterestFeatureScheduledTaskConfiguration()

  @Test
  fun `should configure interest task`() {
    // given
    val task = InterestFeatureScheduledTask(repo, kafka)
    val props = randomInterestFeatureConfigurationProperties()

    // when
    val actual = cfg.interestTaskDetails(task, props)

    // then
    val now =
      Instant
        .now()
        .atZone(ZoneOffset.UTC)
        .withHour(props.scheduledTask.effectiveTimestampHour)
        .withMinute(props.scheduledTask.effectiveTimestampMinute)
        .withSecond(props.scheduledTask.effectiveTimestampSecond)
        .truncatedTo(ChronoUnit.SECONDS)
        .toInstant()
    val expected =
      ScheduledTaskDetails(
        beanClass = InterestFeatureScheduledTask::class.java,
        cronExpression = props.scheduledTask.cronExpression,
        initialJobData =
          InterestFeatureScheduledTaskData(
            effectiveTimestamp = now,
            accrualTimestamp =
              now.plusMillis(props.scheduledTask.accrualIncrementDuration.toMillis()),
            applicationTimestamp =
              now.plusMillis(props.scheduledTask.applicationIncrementDuration.toMillis()),
            incrementDuration = props.scheduledTask.incrementDuration,
          ),
        dataClass = InterestFeatureScheduledTaskData::class.java,
      )
    assertThat(actual).isEqualTo(expected)
  }
}
