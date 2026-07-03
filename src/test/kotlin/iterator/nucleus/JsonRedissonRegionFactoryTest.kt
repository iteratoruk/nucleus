package iterator.nucleus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.redisson.codec.JsonJacksonCodec
import java.math.BigDecimal
import java.time.Instant

class JsonRedissonRegionFactoryTest {
  data class Sample(
    val amount: BigDecimal,
    val at: Instant,
    val name: String,
  )

  @Test
  fun `builds a JSON Jackson codec rather than Java serialisation`() {
    val config =
      JsonRedissonRegionFactory.buildConfig(JsonRedissonRegionFactory.DEFAULT_CONFIG_PATH)

    assertThat(config.codec).isInstanceOf(JsonJacksonCodec::class.java)
  }

  @Test
  fun `codec carries the application Jackson configuration`() {
    val codec =
      JsonRedissonRegionFactory.buildConfig(JsonRedissonRegionFactory.DEFAULT_CONFIG_PATH).codec
        as JsonJacksonCodec
    val mapper = codec.objectMapper
    val sample = Sample(BigDecimal("1.50"), Instant.parse("2020-01-01T00:00:00Z"), "Aardvark")

    val json = mapper.writeValueAsString(sample)

    // BigDecimal as a JSON string (the app rule) and readable JSON, not Java serialisation.
    assertThat(json).contains("\"1.50\"").contains("Aardvark")
    // Round-trips only because the Kotlin and JavaTime modules from the app mapper are present —
    // a bare codec mapper could not construct a Kotlin data class.
    assertThat(mapper.readValue(json, Sample::class.java)).isEqualTo(sample)
  }

  @Test
  fun `building the codec does not mutate the shared application mapper`() {
    val probe = mapOf("k" to "v")
    val before = Serialization.mapper.writeValueAsString(probe)

    JsonRedissonRegionFactory.buildConfig(JsonRedissonRegionFactory.DEFAULT_CONFIG_PATH)

    // The codec's default typing must stay on the copy, never on the app-wide mapper.
    val after = Serialization.mapper.writeValueAsString(probe)
    assertThat(after).isEqualTo(before)
    assertThat(after).doesNotContain("@class")
  }
}
