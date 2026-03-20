package iterator.nucleus.audit

import iterator.nucleus.LoggerDelegate
import org.springframework.context.ApplicationEventPublisher

class MockAuditService(
  publisher: ApplicationEventPublisher,
) : AuditService(publisher) {
  companion object {
    private val LOG by LoggerDelegate()
  }

  private val auditEvents = mutableMapOf<NucleusAuditEventType, MutableList<AbstractAuditEvent>>()

  override fun publishAuditEvent(event: AbstractAuditEvent) {
    LOG.info("Received audit event: {}", event)
    val type = NucleusAuditEventType.valueOf(event.auditEvent.type)
    if (!auditEvents.containsKey(type)) {
      auditEvents[type] = mutableListOf()
    }
    auditEvents[type]!!.add(event)
  }

  fun clear() {
    auditEvents.clear()
  }

  fun getAuditEvents(type: NucleusAuditEventType): List<AbstractAuditEvent> = auditEvents[type] ?: emptyList()
}
