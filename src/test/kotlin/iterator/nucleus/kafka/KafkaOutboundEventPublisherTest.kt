package iterator.nucleus.kafka

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.core.KafkaTemplate

@ExtendWith(MockitoExtension::class)
class KafkaOutboundEventPublisherTest(
  @Mock val applicationEventPublisher: ApplicationEventPublisher,
  @Mock val kafkaTemplate: KafkaTemplate<String, Any>,
) {
  val publisher = KafkaOutboundEventPublisher(applicationEventPublisher, kafkaTemplate)

  @Test
  fun `publish defers the send to commit and raises no audit event`() {
    val event = TestOutboundEvent()

    publisher.publish(event)

    // Publishing emits only the intermediary OutboundEventReady: nothing is sent to Kafka before
    // commit, and — since the audit fan-out has been removed — no audit event is raised.
    verify(applicationEventPublisher).publishEvent(OutboundEventReady(event))
    verifyNoMoreInteractions(applicationEventPublisher)
    verifyNoMoreInteractions(kafkaTemplate)
  }

  @Test
  fun `onCommit sends the event to Kafka`() {
    val event = TestOutboundEvent()

    publisher.onCommit(OutboundEventReady(event))

    verify(kafkaTemplate).send(event.topic, event.key, event)
  }
}

class TestOutboundEvent(
  override val topic: String = "${KafkaConstants.PRIVATE_TOPIC_PREFIX}test",
  override val key: String = "k",
) : OutboundEvent()
