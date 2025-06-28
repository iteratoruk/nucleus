package iterator.nucleus.ledger

import iterator.nucleus.TestingFu.randomKafkaConfigurationProperties
import iterator.nucleus.getPrivateFieldValue
import org.apache.kafka.clients.admin.NewTopic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class LedgerConfigurationTest {
  companion object {
    @JvmStatic fun expectedTopics(): Array<String> = arrayOf(LedgerTopics.WITHDRAWALS)
  }

  val cfg = LedgerConfiguration()

  @MethodSource("expectedTopics")
  @ParameterizedTest(
    name = "should create topic {0} with correct num partitions and replication factor",
  )
  fun `should create topics`(topic: String) {
    // given
    val props = randomKafkaConfigurationProperties()

    // when
    val topics = cfg.ledgerTopics(props)

    // then
    val actual: NewTopic =
      (topics.getPrivateFieldValue<Collection<NewTopic>>("newTopics"))!!.first {
        it.name() == topic
      }
    assertThat(actual.numPartitions()).isEqualTo(props.numberOfPartitions)
    assertThat(actual.replicationFactor()).isEqualTo(props.replicationFactor)
  }
}
