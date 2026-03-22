package iterator.nucleus.idempotency

import com.fasterxml.jackson.core.JacksonException
import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.NucleusErrorCode
import iterator.nucleus.NucleusInternalErrorException
import iterator.nucleus.Serialization
import jakarta.persistence.Entity
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import kotlin.reflect.KClass

@Entity
class IdempotentOperation(
  val operationId: String,
  val idempotencyKey: String,
  val uri: String,
  val responseBody: String,
) : AbstractJpaEntity()

@Repository
interface IdempotentOperationRepository : AbstractJpaRepository<IdempotentOperation> {
  fun findByOperationIdAndIdempotencyKey(
    operationId: String,
    idempotencyKey: String,
  ): IdempotentOperation?
}

@Service
class IdempotencyService(
  private val repository: IdempotentOperationRepository,
) {
  fun <T : Any> findExistingResponse(
    operationId: String,
    idempotencyKey: String,
    type: KClass<T>,
  ): T? {
    val operation =
      repository.findByOperationIdAndIdempotencyKey(operationId, idempotencyKey) ?: return null
    return try {
      Serialization.mapper.readValue(operation.responseBody, type.java)
    } catch (e: JacksonException) {
      throw NucleusInternalErrorException(
        code = NucleusErrorCode.IDEMPOTENT_OPERATION_RESPONSE_UNREADABLE,
        message = "The stored response for this idempotency key could not be read",
        cause = e,
      )
    }
  }

  fun <T : Any> record(
    operationId: String,
    idempotencyKey: String,
    uri: String,
    response: T,
  ) {
    repository.save(
      IdempotentOperation(
        operationId = operationId,
        idempotencyKey = idempotencyKey,
        uri = uri,
        responseBody = Serialization.mapper.writeValueAsString(response),
      ),
    )
  }
}
