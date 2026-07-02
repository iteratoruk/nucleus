package iterator.nucleus

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus

class NucleusInternalErrorException(
  val code: NucleusErrorCode,
  override val message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

@ControllerAdvice
class ErrorHandler {
  @ResponseBody
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  @ExceptionHandler(NucleusInternalErrorException::class)
  fun handleInternalError(e: NucleusInternalErrorException): NucleusError =
    NucleusError(
      code = e.code,
      message = e.message,
    )
}

data class NucleusError(
  val code: NucleusErrorCode,
  val message: String,
)

enum class NucleusErrorCode {
  IDEMPOTENT_OPERATION_RESPONSE_UNREADABLE,
}
