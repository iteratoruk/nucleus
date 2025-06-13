package iterator.nucleus

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import iterator.nucleus.kafka.NucleusDeserializer
import iterator.nucleus.kafka.NucleusSerializer
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.kafka.annotation.EnableKafkaRetryTopic
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.retry.annotation.EnableRetry
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

@EnableAsync
@EnableRetry
@EnableScheduling
@EnableJpaAuditing
@EnableKafkaRetryTopic
@SpringBootApplication
class App {
  companion object {
    @JvmStatic
    @Suppress("SpreadOperator")
    fun main(args: Array<String>) {
      runApplication<App>(*args)
    }
  }

  @Bean fun objectMapper(): ObjectMapper = Serialization.mapper

  @Bean
  fun lockProvider(dataSource: DataSource): LockProvider = JdbcTemplateLockProvider(dataSource)

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

object NucleusHeaders {
  const val CLIENT_ID = "X-Client-ID"
}

object Serialization {
  val mapper: ObjectMapper =
    ObjectMapper()
      .findAndRegisterModules()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
      .configure(SerializationFeature.WRITE_DATES_WITH_ZONE_ID, true)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL)
}

object SchedulerLockNames {
  const val INTEREST = "INTEREST"
}
