package iterator.nucleus.account.feature.interest

import iterator.nucleus.TestingFu.randomInterestFeatureConfigurationProperties
import iterator.nucleus.getPrivateFieldValue
import org.apache.kafka.clients.admin.NewTopic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class InterestFeatureKafkaConfigurationTest {
  companion object {
    @JvmStatic
    fun expectedTopics(): Stream<Arguments> =
      Stream.of(
        Arguments.of(InterestFeatureTopics.CONFIGURE_INTEREST),
      )
  }

  val cfg = InterestFeatureKafkaConfiguration()

  @MethodSource("expectedTopics")
  @ParameterizedTest(
    name = "should create topic {0} with correct num partitions and replication factor",
  )
  fun `should create topics`(topic: String) {
    // given
    val props = randomInterestFeatureConfigurationProperties()

    // when
    val topics = cfg.interestFeatureTopics(props)

    // then
    val actual: NewTopic =
      (topics.getPrivateFieldValue<Collection<NewTopic>>("newTopics"))!!.first {
        it.name() == topic
      }
    assertThat(actual.numPartitions()).isEqualTo(props.kafka.numberOfPartitions)
    assertThat(actual.replicationFactor()).isEqualTo(props.kafka.replicationFactor)
  }
}
