package iterator.nucleus.account.feature.interest

import iterator.nucleus.schedule.ScheduledTaskDetails
import iterator.nucleus.schedule.scheduledTask
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Configuration
class InterestFeatureScheduledTaskConfiguration {
  @Bean
  fun interestTaskDetails(
    task: InterestFeatureScheduledTask,
    props: InterestFeatureConfigurationProperties,
  ): ScheduledTaskDetails<InterestFeatureScheduledTaskData> {
    val endOfDayToday =
      Instant
        .now()
        .atZone(ZoneOffset.UTC)
        .withHour(props.scheduledTask.effectiveTimestampHour)
        .withMinute(props.scheduledTask.effectiveTimestampMinute)
        .withSecond(props.scheduledTask.effectiveTimestampSecond)
        .truncatedTo(ChronoUnit.SECONDS)
        .toInstant()
    return scheduledTask(
      bean = task,
      cronExpression = props.scheduledTask.cronExpression,
      initialJobData =
        InterestFeatureScheduledTaskData(
          effectiveTimestamp = endOfDayToday,
          incrementDuration = props.scheduledTask.incrementDuration,
          accrualTimestamp =
            endOfDayToday.plusMillis(
              props.scheduledTask.accrualIncrementDuration.toMillis(),
            ),
          applicationTimestamp =
            endOfDayToday.plusMillis(
              props.scheduledTask.applicationIncrementDuration.toMillis(),
            ),
        ),
    )
  }
}
