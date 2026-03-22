package iterator.nucleus

import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus

class NucleusValidationException(
  val violations: List<NucleusViolation>,
) : RuntimeException()

class NucleusInternalErrorException(
  val code: NucleusErrorCode,
  override val message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

@ControllerAdvice
class ErrorHandler {
  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(MissingRequestHeaderException::class)
  fun handleMissingHeader(e: MissingRequestHeaderException): NucleusError =
    NucleusError(
      code = NucleusErrorCode.MISSING_HEADER,
      message = "Missing ${e.headerName} header",
    )

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(NucleusValidationException::class)
  fun handleValidation(e: NucleusValidationException): NucleusError =
    NucleusError(
      code = NucleusErrorCode.INVALID_FEATURE_CONFIGURATION,
      message = "Validation failed",
      violations = e.violations,
    )

  @ResponseBody
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  @ExceptionHandler(NucleusInternalErrorException::class)
  fun handleInternalError(e: NucleusInternalErrorException): NucleusError =
    NucleusError(
      code = e.code,
      message = e.message,
    )
}

data class NucleusViolation(
  val subject: String,
  val message: String,
)

data class NucleusError(
  val code: NucleusErrorCode,
  val message: String,
  val violations: List<NucleusViolation>? = null,
)

enum class NucleusErrorCode {
  MISSING_HEADER,
  INVALID_FEATURE_CONFIGURATION,
  IDEMPOTENT_OPERATION_RESPONSE_UNREADABLE,
}
