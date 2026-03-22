package iterator.nucleus.audit

import com.fasterxml.jackson.databind.ObjectMapper
import iterator.nucleus.LoggerDelegate
import iterator.nucleus.schedule.ScheduledTaskStatus
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

data class ScheduledTaskStartedEvent(
  val taskName: String,
) : AbstractAuditEvent(
    type = NucleusAuditEventType.SCHEDULED_TASK_STARTED,
    data =
      mapOf(
        "taskName" to taskName,
      ),
  )

data class ScheduledTaskFinishedEvent(
  val taskName: String,
  val status: ScheduledTaskStatus,
  val executionDuration: Long,
  val error: Exception? = null,
) : AbstractAuditEvent(
    type = NucleusAuditEventType.SCHEDULED_TASK_FINISHED,
    data =
      (error?.let { mapOf("error" to (it.message as Any)) } ?: emptyMap()) +
        mapOf(
          "taskName" to taskName,
          "status" to status.name,
          "executionDuration" to executionDuration,
        ),
  )

enum class NucleusAuditEventType {
  NODE_CREATED,
  PARAMETER_VALUE_SET,
  PARAMETER_VALUE_SUPERSEDED,
  SCHEDULED_TASK_STARTED,
  SCHEDULED_TASK_FINISHED,
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
