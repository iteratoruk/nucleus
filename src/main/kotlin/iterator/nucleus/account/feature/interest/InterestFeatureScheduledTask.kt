package iterator.nucleus.account.feature.interest

import iterator.nucleus.account.AccountFeatureRepository
import iterator.nucleus.account.feature.FeatureConstants
import iterator.nucleus.schedule.ScheduledTask
import iterator.nucleus.schedule.ScheduledTaskResult
import iterator.nucleus.schedule.ScheduledTaskStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Component
class InterestFeatureScheduledTask(
  val repo: AccountFeatureRepository,
  val kafka: KafkaTemplate<String, Any>,
) : ScheduledTask<InterestFeatureScheduledTaskData> {
  @Transactional
  override fun run(data: InterestFeatureScheduledTaskData): ScheduledTaskResult<InterestFeatureScheduledTaskData> {
    repo.findByName(FeatureConstants.INTEREST_FEATURE_NAME)?.accounts?.forEach { account ->
      kafka.send(
        InterestFeatureTopics.CONFIGURE_INTEREST,
        ConfigureInterestFeatureMessage(
          accountId = account.accountId,
          effectiveTimestamp = data.effectiveTimestamp,
          accrualTimestamp = data.accrualTimestamp,
          applicationTimestamp = data.applicationTimestamp,
        ),
      )
    }
    return ScheduledTaskResult(
      status = ScheduledTaskStatus.SUCCESS,
      data =
        data.copy(
          effectiveTimestamp =
            data.effectiveTimestamp.plusMillis(data.incrementDuration.toMillis()),
          accrualTimestamp =
            data.accrualTimestamp.plusMillis(data.incrementDuration.toMillis()),
          applicationTimestamp =
            data.applicationTimestamp.plusMillis(data.incrementDuration.toMillis()),
        ),
    )
  }
}

data class InterestFeatureScheduledTaskData(
  val effectiveTimestamp: Instant,
  val accrualTimestamp: Instant,
  val applicationTimestamp: Instant,
  val incrementDuration: Duration = Duration.ofDays(1),
)
