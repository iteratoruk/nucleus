package iterator.nucleus.ledger

import iterator.nucleus.TestingFu.randomAlphabetic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class LedgerTopicTypeMapperTest {
  companion object {
    @JvmStatic
    fun expectedTopicMappings(): Stream<Arguments> =
      Stream.of(
        Arguments.of(randomAlphabetic(16), null),
        Arguments.of(LedgerTopics.WITHDRAWALS, WithdrawalMessage::class.java),
        Arguments.of("${LedgerTopics.WITHDRAWALS}-dlt", WithdrawalMessage::class.java),
      )
  }

  val mapper = LedgerTopicTypeMapper()

  @MethodSource("expectedTopicMappings")
  @ParameterizedTest(name = "should map {0} to {1}")
  fun `should map topics`(
    topic: String,
    type: Class<*>?,
  ) {
    assertThat(mapper.resolveType(topic)).isEqualTo(type)
  }
}
