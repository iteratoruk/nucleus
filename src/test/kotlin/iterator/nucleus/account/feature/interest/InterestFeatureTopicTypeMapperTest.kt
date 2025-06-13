package iterator.nucleus.account.feature.interest

import iterator.nucleus.TestingFu.randomAlphabetic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class InterestFeatureTopicTypeMapperTest {
  companion object {
    @JvmStatic
    fun expectedTopicMappings(): Stream<Arguments> =
      Stream.of(
        Arguments.of(randomAlphabetic(16), null),
        Arguments.of(
          InterestFeatureTopics.CONFIGURE_INTEREST,
          ConfigureInterestFeatureMessage::class.java,
        ),
        Arguments.of(
          InterestFeatureTopics.COMMITTED_BALANCE,
          GetCommittedBalanceMessage::class.java,
        ),
        Arguments.of(InterestFeatureTopics.ACCRUE_INTEREST, InterestAccrualMessage::class.java),
        Arguments.of(
          InterestFeatureTopics.ACCRUE_BONUS_INTEREST,
          InterestAccrualMessage::class.java,
        ),
        Arguments.of(
          InterestFeatureTopics.COALESCE_ACCRUED_INTEREST,
          CoalesceAccruedInterestMessage::class.java,
        ),
        Arguments.of(InterestFeatureTopics.APPLY_INTEREST, ApplyInterestMessage::class.java),
      )
  }

  val mapper = InterestFeatureTopicTypeMapper()

  @MethodSource("expectedTopicMappings")
  @ParameterizedTest(name = "should map {0} to {1}")
  fun `should map topics`(
    topic: String,
    type: Class<*>?,
  ) {
    assertThat(mapper.resolveType(topic)).isEqualTo(type)
  }
}
