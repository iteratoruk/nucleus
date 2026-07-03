package iterator.nucleus.idempotency

import iterator.nucleus.TestingFu.randomAlphanumeric
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import kotlin.reflect.KClass

@ExtendWith(MockitoExtension::class)
class IdempotencyAspectTest(
  @Mock val idempotencyService: IdempotencyService,
) {
  val aspect = IdempotencyAspect(idempotencyService)

  @Test
  @Suppress("UNCHECKED_CAST")
  fun `recordOrReplay replays the stored response when the record hits a unique constraint`() {
    val operationId = randomAlphanumeric(8)
    val key = randomAlphanumeric(16)
    val uri = "/api/v1/probe"
    val produced = randomAlphanumeric(8)
    val stored = randomAlphanumeric(8)
    val type = String::class as KClass<Any>
    // a concurrent first request recorded between the miss and this write
    doThrow(DataIntegrityViolationException("duplicate"))
      .whenever(idempotencyService)
      .record(operationId, key, uri, produced)
    whenever(idempotencyService.findExistingResponse(operationId, key, type)).thenReturn(stored)

    val result = aspect.recordOrReplay(operationId, key, uri, produced, type)

    // converge on the stored response rather than surfacing the constraint violation
    assertThat(result).isEqualTo(stored)
  }

  @Test
  @Suppress("UNCHECKED_CAST")
  fun `recordOrReplay returns the produced response when the record succeeds`() {
    val operationId = randomAlphanumeric(8)
    val key = randomAlphanumeric(16)
    val uri = "/api/v1/probe"
    val produced = randomAlphanumeric(8)
    val type = String::class as KClass<Any>

    val result = aspect.recordOrReplay(operationId, key, uri, produced, type)

    assertThat(result).isEqualTo(produced)
  }
}
