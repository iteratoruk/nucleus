package iterator.nucleus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import kotlin.random.Random

class LoggerDelegateTest {
  // simple random test to ensure we are not returning a hard-coded value
  // and are unwrapping the logger name correctly; using range 0..2 ensures
  // either logger can be selected
  @Test
  fun `logger delegate should return logger for enclosing type`() {
    // given
    val type: UnwrapLogger =
      listOf(TestUnwrapLoggerOne(), TestUnwrapLoggerTwo())[Random.nextInt(0, 2)]

    // when
    val logger = type.getLog()

    // then
    assertThat(logger.name).isEqualTo(type.javaClass.name)
  }
}

interface UnwrapLogger {
  fun getLog(): Logger
}

class TestUnwrapLoggerOne : UnwrapLogger {
  companion object {
    val LOG: Logger by LoggerDelegate()
  }

  override fun getLog(): Logger = LOG
}

class TestUnwrapLoggerTwo : UnwrapLogger {
  companion object {
    val LOG: Logger by LoggerDelegate()
  }

  override fun getLog(): Logger = LOG
}
