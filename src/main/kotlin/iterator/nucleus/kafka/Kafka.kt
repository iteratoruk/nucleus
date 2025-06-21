package iterator.nucleus.kafka

import iterator.nucleus.Serialization
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.kafka.core.KafkaAdmin.NewTopics
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.declaredMemberProperties

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
    private val CACHE: ConcurrentHashMap<String, Class<*>?> = ConcurrentHashMap()
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
}

data class KafkaRetryConfigurationProperties(
  val maxAttempts: Int,
  val delay: Long,
  val multiplier: Double,
  val maxDelay: Long,
)

data class KafkaConfigurationProperties(
  val numberOfPartitions: Int,
  val replicationFactor: Short,
  val retry: KafkaRetryConfigurationProperties,
)
