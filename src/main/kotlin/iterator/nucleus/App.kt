package iterator.nucleus

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.jsontype.TypeSerializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.retry.annotation.EnableRetry
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import java.math.BigDecimal
import java.time.Clock

@EnableAsync
@EnableRetry
@EnableScheduling
@EnableJpaAuditing
@SpringBootApplication
@EnableAspectJAutoProxy(proxyTargetClass = true)
class App {
  companion object {
    @JvmStatic
    @Suppress("SpreadOperator")
    fun main(args: Array<String>) {
      runApplication<App>(*args)
    }
  }

  @Bean fun objectMapper(): ObjectMapper = Serialization.mapper

  @Bean fun clock(): Clock = Clock.systemUTC()
}

class BigDecimalFromStringDeserializer : StdDeserializer<BigDecimal>(BigDecimal::class.java) {
  override fun deserialize(
    p: JsonParser,
    ctxt: DeserializationContext,
  ): BigDecimal =
    try {
      BigDecimal(p.text)
    } catch (e: NumberFormatException) {
      throw ctxt.weirdStringException(
        p.text,
        BigDecimal::class.java,
        e.message ?: "not a valid decimal number",
      )
    }
}

class BigDecimalToStringSerializer : StdSerializer<BigDecimal>(BigDecimal::class.java) {
  override fun serialize(
    value: BigDecimal,
    gen: JsonGenerator,
    provider: SerializerProvider,
  ) {
    gen.writeString(value.toString())
  }

  // Support polymorphic default typing, which the Redis L2-cache codec activates. Without this a
  // BigDecimal serialised under an active TypeSerializer fails with "Type id handling not
  // implemented". This override is only invoked when default typing is in effect, so the plain
  // HTTP/Kafka wire format (which uses no typing) is unaffected.
  override fun serializeWithType(
    value: BigDecimal,
    gen: JsonGenerator,
    provider: SerializerProvider,
    typeSer: TypeSerializer,
  ) {
    val typeId = typeSer.typeId(value, JsonToken.VALUE_STRING)
    typeSer.writeTypePrefix(gen, typeId)
    serialize(value, gen, provider)
    typeSer.writeTypeSuffix(gen, typeId)
  }
}

object NucleusHeaders {
  const val CLIENT_ID = "X-Client-ID"
  const val IDEMPOTENCY_KEY = "Idempotency-Key"
}

object Serialization {
  val mapper: ObjectMapper =
    ObjectMapper()
      .findAndRegisterModules()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
      .configure(SerializationFeature.WRITE_DATES_WITH_ZONE_ID, true)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL)
      .registerModule(
        SimpleModule()
          .addDeserializer(BigDecimal::class.java, BigDecimalFromStringDeserializer())
          .addSerializer(BigDecimal::class.java, BigDecimalToStringSerializer()),
      )
}

object Uris {
  const val API_V1 = "/api/v1"
}
