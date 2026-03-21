package iterator.nucleus.parameters

import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.audit.AbstractAuditEvent
import iterator.nucleus.audit.AuditService
import iterator.nucleus.audit.NucleusAuditEventType
import jakarta.persistence.Entity
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

  override fun toString(): String = value
}

@Entity
class ParameterNode(
  val classificationCode: String,
  val ledgerSide: String,
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
) : AbstractJpaEntity()

interface ParameterValueRepository : AbstractJpaRepository<ParameterValue>

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
    val existingNode = parameterNodeRepository.findByClassificationCode(code.value)
    val node =
      existingNode
        ?: parameterNodeRepository.save(
          ParameterNode(classificationCode = code.value, ledgerSide = code.ledgerSide.name),
        )

    if (existingNode == null) {
      auditService.publishAuditEvent(
        NodeCreatedEvent(
          classificationCode = code.value,
          ledgerSide = code.ledgerSide.name,
          creationTimestamp = node.createdDate!!,
        ),
      )
    }

    parameterValues.forEach { (parameterKey, value) ->
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
        ),
      )
    }
  }
}

data class NodeCreatedEvent(
  val classificationCode: String,
  val ledgerSide: String,
  val creationTimestamp: Instant = Instant.now(),
) : AbstractAuditEvent(
    type = NucleusAuditEventType.NODE_CREATED,
    data =
      mapOf(
        "classificationCode" to classificationCode,
        "ledgerSide" to ledgerSide,
      ),
  )

data class ParameterValueSetEvent(
  val classificationCode: String,
  val parameterKey: String,
  val effectiveDatetime: Instant,
  val value: String,
  val writeTimestamp: Instant = Instant.now(),
  val author: String? = null,
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
