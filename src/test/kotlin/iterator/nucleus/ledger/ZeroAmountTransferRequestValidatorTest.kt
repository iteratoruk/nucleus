package iterator.nucleus.ledger

import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.TestingFu.randomEnum
import iterator.nucleus.truncatedToPostgresAccuracy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import java.math.BigDecimal
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ZeroAmountTransferRequestValidatorTest {
  lateinit var validator: ZeroAmountTransferRequestValidator

  @BeforeEach
  fun setup() {
    validator = ZeroAmountTransferRequestValidator()
  }

  @Test
  fun `should throw given request amount is zero when validate`() {
    // given
    val request =
      CreateTransferRequest(
        fromAccount = aValidAccount(aValidAccountTemplate()),
        fromAddress = randomAlphanumeric(16),
        toAccount = aValidAccount(aValidAccountTemplate()),
        toAddress = randomAlphanumeric(16),
        amount = BigDecimal.ZERO,
        type = randomEnum(),
        timestamp = Instant.now().truncatedToPostgresAccuracy(),
      )

    // when ... then
    assertThrows<IllegalArgumentException> { validator.validate(request) }
  }

  @Test
  fun `should throw given request amount is negative when validate`() {
    // given
    val request =
      CreateTransferRequest(
        fromAccount = aValidAccount(aValidAccountTemplate()),
        fromAddress = randomAlphanumeric(16),
        toAccount = aValidAccount(aValidAccountTemplate()),
        toAddress = randomAlphanumeric(16),
        amount = "-0.01".toBigDecimal(),
        type = randomEnum(),
        timestamp = Instant.now().truncatedToPostgresAccuracy(),
      )

    // when ... then
    assertThrows<IllegalArgumentException> { validator.validate(request) }
  }

  @Test
  fun `should not throw given request amount is positive when validate`() {
    // given
    val request =
      CreateTransferRequest(
        fromAccount = aValidAccount(aValidAccountTemplate()),
        fromAddress = randomAlphanumeric(16),
        toAccount = aValidAccount(aValidAccountTemplate()),
        toAddress = randomAlphanumeric(16),
        amount = "0.01".toBigDecimal(),
        type = randomEnum(),
        timestamp = Instant.now().truncatedToPostgresAccuracy(),
      )

    // when ... then
    assertDoesNotThrow { validator.validate(request) }
  }
}
