package iterator.nucleus.ledger

import iterator.nucleus.kafka.KafkaConfigurationProperties
import iterator.nucleus.kafka.KafkaConfigurationUtils
import iterator.nucleus.kafka.KafkaConstants
import iterator.nucleus.kafka.RegexTopicMessageTypeMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Configuration
class LedgerConfiguration {
  @Bean
  fun ledgerTopics(cfg: KafkaConfigurationProperties): KafkaAdmin.NewTopics =
    KafkaConfigurationUtils.toNewTopics(
      obj = LedgerTopics,
      numberOfPartitions = cfg.numberOfPartitions,
      replicationFactor = cfg.replicationFactor,
    )
}

object LedgerTopics {
  const val WITHDRAWALS = "${KafkaConstants.PRIVATE_TOPIC_PREFIX}.ledger.withdrawals"
}

data class WithdrawalMessage(
  val accountId: UUID,
  val operationId: UUID,
  val timestamp: Instant = Instant.now(),
)

@Component
class LedgerTopicTypeMapper :
  RegexTopicMessageTypeMapper(
    mappings = mapOf(LedgerTopics.WITHDRAWALS to WithdrawalMessage::class.java),
  )
