package iterator.nucleus.audit

import com.fasterxml.jackson.databind.ObjectMapper
import iterator.nucleus.Serialization
import iterator.nucleus.TestingFu.randomAlphabetic
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.TestingFu.randomInstant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.slf4j.Logger
import org.springframework.context.ApplicationEventPublisher
import kotlin.random.Random

@ExtendWith(MockitoExtension::class)
class AuditTest {
  @Test
  fun `should publish audit event`() {
    // given
    val publisher = mock<ApplicationEventPublisher> {}
    val service = AuditService(publisher)
    val event =
      GenericAuditEvent(
        type = randomEnumValue<NucleusAuditEventType>(),
        principal = randomAlphabetic(16),
        data = mapOf(randomAlphanumeric(8) to randomAlphanumeric(8)),
        timestamp = randomInstant(),
      )

    // when
    service.publishAuditEvent(event)

    // then
    verify(publisher).publishEvent(eq(event))
  }

  @Test
  fun `logging audit repository logs audit event using object mapper`() {
    // given
    val event =
      GenericAuditEvent(
        type = randomEnumValue<NucleusAuditEventType>(),
        principal = randomAlphabetic(16),
        data = mapOf(randomAlphanumeric(8) to randomAlphanumeric(8)),
        timestamp = randomInstant(),
      )
    val message = randomAlphanumeric(32)
    val mapper =
      mock<ObjectMapper> { on { writeValueAsString(eq(event.auditEvent)) } doReturn message }
    val log = mock<Logger> {}
    val repo =
      object : LoggingAuditRepository(mapper) {
        override fun getLog(): Logger = log
      }

    // when
    repo.add(event.auditEvent)

    // then
    verify(log).info(message)
  }

  @Test
  fun `should throw when find in logging audit repository`() {
    // given
    val repo = LoggingAuditRepository(Serialization.mapper)

    // when ... then
    assertThrows<UnsupportedOperationException> {
      repo.find(randomAlphanumeric(16), randomInstant(), randomAlphanumeric(16))
    }
  }

  private inline fun <reified T : Enum<T>> randomEnumValue(): T = enumValues<T>()[Random.nextInt(enumValues<T>().size)]
}
