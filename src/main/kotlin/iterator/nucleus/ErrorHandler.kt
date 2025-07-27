package iterator.nucleus

import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.ResponseStatus

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
}

data class NucleusError(
  val code: NucleusErrorCode,
  val message: String,
)

enum class NucleusErrorCode {
  MISSING_HEADER,
}
