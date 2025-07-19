package iterator.nucleus

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.retry.annotation.EnableRetry
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

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
