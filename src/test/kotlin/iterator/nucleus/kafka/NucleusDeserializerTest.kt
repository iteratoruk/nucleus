package iterator.nucleus.kafka

import iterator.nucleus.Serialization
import iterator.nucleus.TestingFu.randomAlphanumeric
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NucleusDeserializerTest {
  @Test
  fun `should throw given unresolvable topic when deserialize`() {
    // given
    val mappings =
      listOf(
        object : TopicMessageTypeMapper {
          override fun resolveType(topic: String): Class<*>? = null
        },
      )
    val deserializer = NucleusDeserializer(mappings)
    val topic = randomAlphanumeric(32)
    val data = randomAlphanumeric(32).toByteArray()

    // when ... then
    assertThrows<IllegalStateException> { deserializer.deserialize(topic, data) }
  }

  @Test
  fun `should deserialize as string given topic resolves to string when deserialize`() {
    // given
    val mappings =
      listOf(
        object : TopicMessageTypeMapper {
          override fun resolveType(topic: String): Class<*>? = String::class.java
        },
      )
    val deserializer = NucleusDeserializer(mappings)
    val topic = randomAlphanumeric(32)
    val data = randomAlphanumeric(32)

    // when
    val actual = deserializer.deserialize(topic, data.toByteArray())

    // then
    assertThat(actual).isEqualTo(data)
  }

  @Test
  fun `should resolve topic type mapping and deserialize as string given topic resolves to string when deserialize`() {
    // given
    val mappings =
      listOf(
        object : TopicMessageTypeMapper {
          override fun resolveType(topic: String): Class<*>? = null
        },
        object : TopicMessageTypeMapper {
          override fun resolveType(topic: String): Class<*>? = String::class.java
        },
      )
    val deserializer = NucleusDeserializer(mappings)
    val topic = randomAlphanumeric(32)
    val data = randomAlphanumeric(32)

    // when
    val actual = deserializer.deserialize(topic, data.toByteArray())

    // then
    assertThat(actual).isEqualTo(data)
  }

  @Test
  fun `should deserialize as complex type given topic resolves to complex type when deserialize`() {
    // given
    val mappings =
      listOf(
        object : TopicMessageTypeMapper {
          override fun resolveType(topic: String): Class<*>? = TestBean::class.java
        },
      )
    val deserializer = NucleusDeserializer(mappings)
    val topic = randomAlphanumeric(32)
    val data = TestBean(randomAlphanumeric(32))
    val bytes = Serialization.mapper.writeValueAsBytes(data)

    // when
    val actual = deserializer.deserialize(topic, bytes)

    // then
    assertThat(actual).isEqualTo(data)
  }
}
