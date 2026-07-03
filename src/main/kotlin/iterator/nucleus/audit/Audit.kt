package iterator.nucleus.audit

import com.fasterxml.jackson.databind.ObjectMapper
import iterator.nucleus.LoggerDelegate
import org.slf4j.Logger
import org.springframework.boot.actuate.audit.AuditEvent
import org.springframework.boot.actuate.audit.AuditEventRepository
import org.springframework.boot.actuate.audit.listener.AuditApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class AuditService(
  val publisher: ApplicationEventPublisher,
) {
  @Async
  fun publishAuditEvent(event: AbstractAuditEvent) {
    publisher.publishEvent(event)
  }
}

abstract class AbstractAuditEvent(
  type: NucleusAuditEventType,
  principal: String? = null,
  data: Map<String, Any> = emptyMap(),
  timestamp: Instant = Instant.now(),
) : AuditApplicationEvent(timestamp, principal, type.name, data)

data class GenericAuditEvent(
  val type: NucleusAuditEventType,
  val principal: String? = null,
  val data: Map<String, Any> = emptyMap(),
  val timestamp: Instant = Instant.now(),
) : AbstractAuditEvent(type, principal, data, timestamp)

/**
 * The type discriminator carried by every audit event and surfaced as the Actuator audit event type
 * string. It is an interface, not a single enum, so each feature package defines and owns its own
 * audit event types (as an enum implementing this) without the audit package depending on any peer
 * package. An enum implementing it satisfies [name] with its constant name.
 */
interface NucleusAuditEventType {
  val name: String
}

@Component
class LoggingAuditRepository(
  val om: ObjectMapper,
) : AuditEventRepository {
  companion object {
    private val LOG: Logger by LoggerDelegate()
  }

  override fun add(event: AuditEvent) {
    getLog().info(om.writeValueAsString(event))
  }

  override fun find(
    principal: String?,
    after: Instant,
    type: String,
  ): List<AuditEvent> = throw UnsupportedOperationException()

  // for mock log injection during tests ONLY
  internal open fun getLog(): Logger = LOG
}
