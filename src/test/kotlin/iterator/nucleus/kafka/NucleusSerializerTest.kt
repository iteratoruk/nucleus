package iterator.nucleus.kafka

import iterator.nucleus.Serialization
import iterator.nucleus.TestingFu.randomAlphanumeric
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NucleusSerializerTest {
  @Test
  fun `should throw given unresolvable topic when serialize`() {
    // given ... no mapping will be found
    val mappings =
      listOf(
        object : TopicMessageTypeMapper {
          override fun resolveType(topic: String): Class<*>? = null
        },
      )
    val serializer = NucleusSerializer(mappings)
    val data = randomAlphanumeric(32)
    val topic = randomAlphanumeric(32)

    // when ... then (
    assertThrows<IllegalStateException> { serializer.serialize(topic, data) }
  }

  @Test
  fun `should serialize as string given topic resolves to string when serialize`() {
    // given
    val mappings =
      listOf(
        object : TopicMessageTypeMapper {
          override fun resolveType(topic: String): Class<*>? = String::class.java
        },
      )
    val serializer = NucleusSerializer(mappings)
    val data = randomAlphanumeric(32)
    val topic = randomAlphanumeric(32)

    // when
    val actual = serializer.serialize(topic, data)

    // then
    assertThat(actual).isEqualTo(data.toByteArray())
  }

  @Test
  fun `should resolve topic type mapping from subsequent mapper and serialize as string given topic resolves to string when serialize`() {
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
    val serializer = NucleusSerializer(mappings)
    val data = randomAlphanumeric(32)
    val topic = randomAlphanumeric(32)

    // when
    val actual = serializer.serialize(topic, data)

    // then
    assertThat(actual).isEqualTo(data.toByteArray())
  }

  @Test
  fun `should serialize as complex type given topic resolves to complex type when serialize`() {
    // given
    val mappings =
      listOf(
        object : TopicMessageTypeMapper {
          override fun resolveType(topic: String): Class<*>? = TestBean::class.java
        },
      )
    val serializer = NucleusSerializer(mappings)
    val data = TestBean(randomAlphanumeric(32))
    val topic = randomAlphanumeric(32)

    // when
    val actual = serializer.serialize(topic, data)

    // then
    val expected = Serialization.mapper.writeValueAsBytes(data)
    assertThat(actual).isEqualTo(expected)
  }
}

data class TestBean(
  val prop: String,
)
