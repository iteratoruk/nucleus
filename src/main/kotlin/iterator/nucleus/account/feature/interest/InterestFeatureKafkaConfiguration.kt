package iterator.nucleus.account.feature.interest

import iterator.nucleus.kafka.KafkaConfigurationUtils
import iterator.nucleus.kafka.TopicMessageTypeMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaAdmin.NewTopics
import org.springframework.stereotype.Component

@Configuration
class InterestFeatureKafkaConfiguration {
  @Bean
  fun interestFeatureTopics(cfg: InterestFeatureConfigurationProperties): NewTopics =
    KafkaConfigurationUtils.toNewTopics(
      obj = InterestFeatureTopics,
      numberOfPartitions = cfg.kafka.numberOfPartitions,
      replicationFactor = cfg.kafka.replicationFactor,
    )
}

@Component
class InterestFeatureTopicTypeMapper : TopicMessageTypeMapper {
  override fun resolveType(topic: String): Class<*>? =
    when {
      topic.startsWith(InterestFeatureTopics.CONFIGURE_INTEREST) ->
        ConfigureInterestFeatureMessage::class.java
      topic.startsWith(InterestFeatureTopics.COMMITTED_BALANCE) ->
        GetCommittedBalanceMessage::class.java
      topic.startsWith(InterestFeatureTopics.ACCRUE_INTEREST) ->
        InterestAccrualMessage::class.java
      topic.startsWith(InterestFeatureTopics.ACCRUE_BONUS_INTEREST) ->
        InterestAccrualMessage::class.java
      topic.startsWith(InterestFeatureTopics.COALESCE_ACCRUED_INTEREST) ->
        CoalesceAccruedInterestMessage::class.java
      topic.startsWith(InterestFeatureTopics.APPLY_INTEREST) -> ApplyInterestMessage::class.java
      else -> null
    }
}
