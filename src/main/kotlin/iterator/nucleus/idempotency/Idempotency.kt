package iterator.nucleus.idempotency

import com.fasterxml.jackson.core.JacksonException
import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.NucleusErrorCode
import iterator.nucleus.NucleusHeaders
import iterator.nucleus.NucleusInternalErrorException
import iterator.nucleus.Serialization
import jakarta.persistence.Entity
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
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

/**
 * Marks a controller function as idempotent. When the request carries an `Idempotency-Key` header,
 * [IdempotencyAspect] brackets the invocation with the store-and-replay logic automatically, so the
 * controller body needs no explicit idempotency handling.
 *
 * [operation] names the logical operation; leave it blank to derive a stable id from the method
 * (`SimpleClassName.methodName`).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Idempotent(
  val operation: String = "",
)

/**
 * Applies store-and-replay idempotency around every [Idempotent] controller function. On a hit it
 * returns the stored response and skips the work; on a miss it performs the work and records the
 * response. Idempotency is applied only when an `Idempotency-Key` header is present; without one
 * (or outside a request) the call proceeds unchanged.
 */
@Aspect
@Component
class IdempotencyAspect(
  private val idempotencyService: IdempotencyService,
) {
  @Around("@annotation(idempotent)")
  fun applyIdempotency(
    joinPoint: ProceedingJoinPoint,
    idempotent: Idempotent,
  ): Any? {
    val request =
      (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
    val idempotencyKey = request?.getHeader(NucleusHeaders.IDEMPOTENCY_KEY)
    if (request == null || idempotencyKey == null) {
      return joinPoint.proceed()
    }

    val signature = joinPoint.signature as MethodSignature
    val operationId =
      idempotent.operation.ifBlank { "${signature.declaringType.simpleName}.${signature.name}" }

    @Suppress("UNCHECKED_CAST")
    val responseType = signature.returnType.kotlin as KClass<Any>

    idempotencyService.findExistingResponse(operationId, idempotencyKey, responseType)?.let {
      return it
    }

    val response = joinPoint.proceed()
    return if (response == null) {
      response
    } else {
      recordOrReplay(operationId, idempotencyKey, request.requestURI, response, responseType)
    }
  }

  // Records the freshly produced response. If a concurrent first request recorded between our miss
  // and our write, the unique constraint fails: replay that stored response so both callers
  // converge
  // on one, rather than surfacing the constraint violation.
  internal fun recordOrReplay(
    operationId: String,
    idempotencyKey: String,
    uri: String,
    response: Any,
    responseType: KClass<Any>,
  ): Any =
    try {
      idempotencyService.record(operationId, idempotencyKey, uri, response)
      response
    } catch (e: DataIntegrityViolationException) {
      idempotencyService.findExistingResponse(operationId, idempotencyKey, responseType)
        ?: throw e
    }
}
