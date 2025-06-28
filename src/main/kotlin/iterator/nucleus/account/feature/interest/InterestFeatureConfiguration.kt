package iterator.nucleus.account.feature.interest

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
@EnableConfigurationProperties(InterestFeatureConfigurationProperties::class)
class InterestFeatureConfiguration

@ConfigurationProperties(prefix = "nucleus.account.features.interest")
data class InterestFeatureConfigurationProperties(
  val scheduledTask: InterestFeatureScheduledTaskConfigurationProperties,
)

data class InterestFeatureScheduledTaskConfigurationProperties(
  val cronExpression: String,
  val effectiveTimestampHour: Int,
  val effectiveTimestampMinute: Int,
  val effectiveTimestampSecond: Int,
  val incrementDuration: Duration,
  val accrualIncrementDuration: Duration,
  val applicationIncrementDuration: Duration,
)
