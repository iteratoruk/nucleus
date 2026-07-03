package iterator.nucleus.audit

import iterator.nucleus.LoggerDelegate
import org.springframework.context.ApplicationEventPublisher

class MockAuditService(
  publisher: ApplicationEventPublisher,
) : AuditService(publisher) {
  companion object {
    private val LOG by LoggerDelegate()
  }

  // Keyed by the Actuator audit event type string: with per-package NucleusAuditEventType enums
  // there is no single enum to reconstruct the type from, but the type string is a stable identity.
  private val auditEvents = mutableMapOf<String, MutableList<AbstractAuditEvent>>()

  override fun publishAuditEvent(event: AbstractAuditEvent) {
    LOG.info("Received audit event: {}", event)
    auditEvents.getOrPut(event.auditEvent.type) { mutableListOf() }.add(event)
  }

  fun clear() {
    auditEvents.clear()
  }

  fun getAuditEvents(type: NucleusAuditEventType): List<AbstractAuditEvent> = auditEvents[type.name] ?: emptyList()
}
