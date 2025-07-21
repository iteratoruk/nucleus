package iterator.nucleus.audit

import iterator.nucleus.LoggerDelegate
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

class MockAuditService(
  publisher: ApplicationEventPublisher,
) : AuditService(publisher) {
  companion object {
    private val LOG by LoggerDelegate()
  }

  private val auditEvents = mutableMapOf<NucleusAuditEventType, MutableList<AbstractAuditEvent>>()

  private val accountLevelAuditEvents =
    mutableMapOf<
      NucleusAuditEventType,
      MutableMap<UUID, MutableList<AbstractAccountLevelAuditEvent>>,
    >()

  override fun publishAuditEvent(event: AbstractAuditEvent) {
    LOG.info("Received audit event: {}", event)
    val type = NucleusAuditEventType.valueOf(event.auditEvent.type)
    if (!auditEvents.containsKey(type)) {
      auditEvents[type] = mutableListOf()
    }
    auditEvents[type]!!.add(event)
    if (event is AbstractAccountLevelAuditEvent) {
      val accountId = event.auditEvent.data["accountId"] as UUID
      if (!accountLevelAuditEvents.containsKey(type)) {
        accountLevelAuditEvents[type] = mutableMapOf()
      }
      if (!accountLevelAuditEvents[type]!!.containsKey(accountId)) {
        accountLevelAuditEvents[type]!![accountId] = mutableListOf()
      }
      accountLevelAuditEvents[type]!![accountId]!!.add(event)
    }
  }

  fun clear() {
    auditEvents.clear()
    accountLevelAuditEvents.clear()
  }

  fun getAuditEvents(type: NucleusAuditEventType): List<AbstractAuditEvent> = auditEvents[type] ?: emptyList()

  fun getAccountLevelAuditEvents(
    type: NucleusAuditEventType,
    accountId: UUID,
  ): List<AbstractAccountLevelAuditEvent> = accountLevelAuditEvents[type]?.get(accountId) ?: emptyList()
}
