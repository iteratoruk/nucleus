package iterator.nucleus.parameters

import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.audit.AbstractAuditEvent
import iterator.nucleus.audit.AuditService
import iterator.nucleus.audit.NucleusAuditEventType
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ManyToOne
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

enum class LedgerSide {
  ASST,
  LIAB,
}

data class ClassificationCode(
  val value: String,
) {
  val ledgerSide: LedgerSide
    get() = LedgerSide.valueOf(value.substringBefore("_"))

  val ancestorCodes: List<ClassificationCode>
    get() {
      val segments = value.split("_")
      return (1 until segments.size).map { ClassificationCode(segments.take(it).joinToString("_")) }
    }

  override fun toString(): String = value
}

@Entity
class ParameterNode(
  val classificationCode: String,
  @Enumerated(EnumType.STRING) val ledgerSide: LedgerSide,
) : AbstractJpaEntity()

interface ParameterNodeRepository : AbstractJpaRepository<ParameterNode> {
  fun findByClassificationCode(classificationCode: String): ParameterNode?
}

@Entity
class ParameterValue(
  @ManyToOne(fetch = FetchType.LAZY) val parameterNode: ParameterNode,
  val parameterKey: String,
  val value: String,
  val effectiveDatetime: Instant,
) : AbstractJpaEntity() {
  var supersededAt: Instant? = null
}

interface ParameterValueRepository : AbstractJpaRepository<ParameterValue> {
  fun findByParameterNodeAndParameterKeyAndEffectiveDatetimeAndSupersededAtIsNull(
    parameterNode: ParameterNode,
    parameterKey: String,
    effectiveDatetime: Instant,
  ): ParameterValue?
}

@Service
@Transactional
class ParameterNodeService(
  val parameterNodeRepository: ParameterNodeRepository,
  val parameterValueRepository: ParameterValueRepository,
  val auditService: AuditService,
) {
  fun write(
    code: ClassificationCode,
    effectiveDatetime: Instant,
    parameterValues: Map<String, String>,
  ) {
    code.ancestorCodes.forEach { ensureNodeExists(it) }
    val node = ensureNodeExists(code)

    parameterValues.forEach { (parameterKey, value) ->
      val existing =
        parameterValueRepository
          .findByParameterNodeAndParameterKeyAndEffectiveDatetimeAndSupersededAtIsNull(
            node,
            parameterKey,
            effectiveDatetime,
          )

      val priorValue =
        if (existing != null) {
          val supersessionTimestamp = Instant.now()
          existing.supersededAt = supersessionTimestamp
          parameterValueRepository.save(existing)
          auditService.publishAuditEvent(
            ParameterValueSupersededEvent(
              classificationCode = code.value,
              parameterKey = parameterKey,
              effectiveDatetime = effectiveDatetime,
              supersededValue = existing.value,
              supersessionTimestamp = supersessionTimestamp,
            ),
          )
          existing.value
        } else {
          null
        }

      val savedValue =
        parameterValueRepository.save(
          ParameterValue(
            parameterNode = node,
            parameterKey = parameterKey,
            value = value,
            effectiveDatetime = effectiveDatetime,
          ),
        )

      auditService.publishAuditEvent(
        ParameterValueSetEvent(
          classificationCode = code.value,
          parameterKey = parameterKey,
          effectiveDatetime = effectiveDatetime,
          value = value,
          writeTimestamp = savedValue.createdDate!!,
          author = savedValue.createdBy,
          priorValue = priorValue,
        ),
      )
    }
  }

  private fun ensureNodeExists(code: ClassificationCode): ParameterNode {
    val existing = parameterNodeRepository.findByClassificationCode(code.value)
    if (existing != null) return existing
    val node =
      parameterNodeRepository.save(
        ParameterNode(classificationCode = code.value, ledgerSide = code.ledgerSide),
      )
    auditService.publishAuditEvent(
      NodeCreatedEvent(
        classificationCode = code.value,
        ledgerSide = code.ledgerSide,
        creationTimestamp = node.createdDate!!,
      ),
    )
    return node
  }
}

data class NodeCreatedEvent(
  val classificationCode: String,
  val ledgerSide: LedgerSide,
  val creationTimestamp: Instant = Instant.now(),
) : AbstractAuditEvent(
    type = NucleusAuditEventType.NODE_CREATED,
    data =
      mapOf(
        "classificationCode" to classificationCode,
        "ledgerSide" to ledgerSide.name,
      ),
  )

data class ParameterValueSetEvent(
  val classificationCode: String,
  val parameterKey: String,
  val effectiveDatetime: Instant,
  val value: String,
  val writeTimestamp: Instant = Instant.now(),
  val author: String? = null,
  val priorValue: String? = null,
) : AbstractAuditEvent(
    type = NucleusAuditEventType.PARAMETER_VALUE_SET,
    data =
      mapOf(
        "classificationCode" to classificationCode,
        "parameterKey" to parameterKey,
        "effectiveDatetime" to effectiveDatetime.toString(),
        "value" to value,
      ),
  )

data class ParameterValueSupersededEvent(
  val classificationCode: String,
  val parameterKey: String,
  val effectiveDatetime: Instant,
  val supersededValue: String,
  val supersessionTimestamp: Instant = Instant.now(),
) : AbstractAuditEvent(
    type = NucleusAuditEventType.PARAMETER_VALUE_SUPERSEDED,
    data =
      mapOf(
        "classificationCode" to classificationCode,
        "parameterKey" to parameterKey,
        "effectiveDatetime" to effectiveDatetime.toString(),
        "supersededValue" to supersededValue,
      ),
  )
