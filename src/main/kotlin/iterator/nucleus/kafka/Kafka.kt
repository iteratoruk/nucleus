package iterator.nucleus.kafka

import iterator.nucleus.Serialization
import iterator.nucleus.audit.AuditService
import iterator.nucleus.audit.GenericAuditEvent
import iterator.nucleus.audit.NucleusAuditEventType
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AliasFor
import org.springframework.kafka.annotation.EnableKafkaRetryTopic
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaAdmin.NewTopics
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.retry.annotation.Backoff
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties

@Configuration
@EnableKafkaRetryTopic
@EnableConfigurationProperties(KafkaConfigurationProperties::class)
class KafkaConfiguration {
  @Bean
  fun producerFactory(
    @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
    serializer: NucleusSerializer,
  ): ProducerFactory<String, Any> {
    val fac =
      DefaultKafkaProducerFactory<String, Any>(
        mapOf(
          ProducerConfig.CLIENT_ID_CONFIG to "nucleus",
          ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
          ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
          ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
          ProducerConfig.ACKS_CONFIG to "all",
        ),
      )
    fac.valueSerializer = serializer
    return fac
  }

  @Bean
  fun consumerFactory(
    @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
    deserializer: NucleusDeserializer,
  ): ConsumerFactory<String, Any> {
    val fac =
      DefaultKafkaConsumerFactory<String, Any>(
        mapOf(
          ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
          ConsumerConfig.CLIENT_ID_CONFIG to "nucleus",
          ConsumerConfig.GROUP_ID_CONFIG to "nucleus",
          ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
          ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to true,
          ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
          ConsumerConfig.METADATA_MAX_AGE_CONFIG to "1000",
        ),
      )
    fac.setValueDeserializer(deserializer)
    return fac
  }

  @Bean
  fun kafkaListenerContainerFactory(consumerFactory: ConsumerFactory<String, Any>): ConcurrentKafkaListenerContainerFactory<String, Any> =
    ConcurrentKafkaListenerContainerFactory<String, Any>().apply {
      this.consumerFactory = consumerFactory
    }

  @Bean
  fun kafkaTemplate(producerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> = KafkaTemplate(producerFactory)

  @Bean
  fun kafkaAdmin(
    @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
  ): KafkaAdmin = KafkaAdmin(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers))
}

fun interface TopicMessageTypeMapper {
  fun resolveType(topic: String): Class<*>?
}

open class RegexTopicMessageTypeMapper(
  val mappings: Map<String, Class<*>>,
) : TopicMessageTypeMapper {
  private val regexes: Map<Regex, Class<*>> =
    mappings.map { Regex("^${it.key.replace(".", "\\.")}.*$") to it.value }.toMap()

  override fun resolveType(topic: String): Class<*>? = regexes.filter { it.key.matches(topic) }.firstNotNullOfOrNull { it.value }
}

abstract class TopicTypeResolver(
  val mappings: List<TopicMessageTypeMapper>,
) {
  fun resolveType(topic: String): Class<*> {
    val type = mappings.firstNotNullOfOrNull { it.resolveType(topic) }
    checkNotNull(type) { "Topic $topic has no type mapping!" }
    return type
  }
}

@Component
class NucleusDeserializer(
  mappings: List<TopicMessageTypeMapper>,
) : TopicTypeResolver(mappings),
  Deserializer<Any>,
  NucleusSerializationBase {
  override val resolver = this
}

@Component
class NucleusSerializer(
  mappings: List<TopicMessageTypeMapper>,
) : TopicTypeResolver(mappings),
  Serializer<Any>,
  NucleusSerializationBase {
  override val resolver = this
}

interface NucleusSerializationBase {
  companion object {
    private val STR_SERIALIZER = StringSerializer()
    private val STR_DESERIALIZER = StringDeserializer()
    private val CACHE: ConcurrentHashMap<String, Class<*>> = ConcurrentHashMap()
  }

  val resolver: TopicTypeResolver

  fun serialize(
    topic: String,
    data: Any,
  ): ByteArray {
    val type = CACHE.computeIfAbsent(topic) { resolver.resolveType(it) }
    if (String::class.java == type) {
      return STR_SERIALIZER.serialize(topic, data as String)
    }
    return Serialization.mapper.writeValueAsBytes(data)
  }

  fun deserialize(
    topic: String,
    data: ByteArray,
  ): Any {
    val type = CACHE.computeIfAbsent(topic) { resolver.resolveType(it) }
    if (String::class.java == type) {
      return STR_DESERIALIZER.deserialize(topic, data)
    }
    return Serialization.mapper.readValue(data, type)
  }
}

@Suppress("SpreadOperator")
object KafkaConfigurationUtils {
  fun toNewTopics(
    obj: Any,
    numberOfPartitions: Int,
    replicationFactor: Short,
  ): NewTopics =
    NewTopics(
      *(
        obj::class
          .declaredMemberProperties
          .filter { it.isConst && it.returnType.classifier == String::class }
          .map { it.getter.call() as String }
          .map { NewTopic(it, numberOfPartitions, replicationFactor) }
          .toTypedArray()
      ),
    )
}

object KafkaConstants {
  const val PRIVATE_TOPIC_PREFIX = "nucleus.private."
  const val PUBLIC_TOPIC_PREFIX = "nucleus.public."
}

abstract class OutboundEvent {
  abstract val topic: String
  abstract val key: String
  abstract val auditType: NucleusAuditEventType
  open val auditPrincipal: String? = null
  open val auditTimestamp: Instant = Instant.now()
  open val auditData: Map<String, Any> = emptyMap()
}

interface OutboundEventPublisher {
  fun publish(event: OutboundEvent)
}

data class OutboundEventReady(
  val event: OutboundEvent,
)

@Component
class KafkaOutboundEventPublisher(
  val applicationEventPublisher: ApplicationEventPublisher,
  val kafkaTemplate: KafkaTemplate<String, Any>,
  val auditService: AuditService,
) : OutboundEventPublisher {
  override fun publish(event: OutboundEvent) {
    auditService.publishAuditEvent(
      GenericAuditEvent(
        type = event.auditType,
        principal = event.auditPrincipal,
        data = event.auditData,
        timestamp = event.auditTimestamp,
      ),
    )
    applicationEventPublisher.publishEvent(OutboundEventReady(event))
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  fun onCommit(ready: OutboundEventReady) {
    kafkaTemplate.send(ready.event.topic, ready.event.key, ready.event)
  }
}

@ConfigurationProperties(prefix = "nucleus.defaults.kafka")
data class KafkaConfigurationProperties(
  val numberOfPartitions: Int,
  val replicationFactor: Short,
  val retry: KafkaRetryConfigurationProperties,
)

data class KafkaRetryConfigurationProperties(
  val maxAttempts: Int,
  val delay: Long,
  val multiplier: Double,
  val maxDelay: Long,
)

@KafkaListener
@Transactional
@RetryableTopic
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class TransactionalRetryingKafkaListener(
  @get:AliasFor(annotation = KafkaListener::class, attribute = "topics")
  val topics: Array<String>,
  @get:AliasFor(annotation = RetryableTopic::class, attribute = "attempts")
  val attempts: String = "\${nucleus.defaults.kafka.retry.max-attempts}",
  @get:AliasFor(annotation = RetryableTopic::class, attribute = "backoff")
  val backoff: Backoff =
    Backoff(
      delayExpression = "\${nucleus.defaults.kafka.retry.delay}",
      multiplierExpression = "\${nucleus.defaults.kafka.retry.multiplier}",
      maxDelayExpression = "\${nucleus.defaults.kafka.retry.max-delay}",
    ),
  @get:AliasFor(annotation = RetryableTopic::class, attribute = "exclude")
  val exclude: Array<KClass<out Throwable>> = [IllegalArgumentException::class],
)
