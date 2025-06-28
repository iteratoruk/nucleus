package iterator.nucleus.account.feature.interest

import iterator.nucleus.kafka.KafkaConfigurationProperties
import iterator.nucleus.kafka.KafkaConfigurationUtils
import iterator.nucleus.kafka.RegexTopicMessageTypeMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaAdmin.NewTopics
import org.springframework.stereotype.Component

@Configuration
class InterestFeatureKafkaConfiguration {
  @Bean
  fun interestFeatureTopics(cfg: KafkaConfigurationProperties): NewTopics =
    KafkaConfigurationUtils.toNewTopics(
      obj = InterestFeatureTopics,
      numberOfPartitions = cfg.numberOfPartitions,
      replicationFactor = cfg.replicationFactor,
    )
}

@Component
class InterestFeatureTopicTypeMapper :
  RegexTopicMessageTypeMapper(
    mappings =
      mapOf(
        InterestFeatureTopics.CONFIGURE_INTEREST to
          ConfigureInterestFeatureMessage::class.java,
        InterestFeatureTopics.COMMITTED_BALANCE to GetCommittedBalanceMessage::class.java,
        InterestFeatureTopics.ACCRUE_INTEREST to InterestAccrualMessage::class.java,
        InterestFeatureTopics.ACCRUE_BONUS_INTEREST to InterestAccrualMessage::class.java,
        InterestFeatureTopics.COALESCE_ACCRUED_INTEREST to
          CoalesceAccruedInterestMessage::class.java,
        InterestFeatureTopics.APPLY_INTEREST to ApplyInterestMessage::class.java,
      ),
  )
