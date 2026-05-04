package iterator.nucleus.kafka

import iterator.nucleus.ApiTestConstants
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.TopicPartition
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Properties

@Component
@Profile(ApiTestConstants.PROFILE_NAME)
class TestOutboundEventCollector(
  val consumerFactory: ConsumerFactory<String, Any>,
) {
  fun <T : Any> eventsOf(
    topic: String,
    type: Class<T>,
    timeout: Duration = Duration.ofSeconds(5),
  ): List<T> {
    val overrides =
      Properties().apply { setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest") }
    val consumer =
      consumerFactory.createConsumer(
        "test-collector-${System.nanoTime()}",
        null,
        null,
        overrides,
      )
    return consumer.use { c ->
      val partitions =
        c.partitionsFor(topic)?.map { TopicPartition(topic, it.partition()) } ?: emptyList()
      if (partitions.isEmpty()) return@use emptyList()
      c.assign(partitions)
      c.seekToBeginning(partitions)
      val records = mutableListOf<T>()
      val deadline = System.nanoTime() + timeout.toNanos()
      while (System.nanoTime() < deadline) {
        val polled = c.poll(Duration.ofMillis(100))
        polled.records(topic).forEach { records.add(type.cast(it.value())) }
        if (records.isNotEmpty()) break
      }
      records
    }
  }

  // No-op kept for AbstractApiTest's BeforeEach hook — the on-demand consumer
  // model is naturally per-test and needs no clearing.
  fun clear() = Unit
}
