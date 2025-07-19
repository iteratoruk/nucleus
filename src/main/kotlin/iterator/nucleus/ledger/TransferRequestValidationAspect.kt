package iterator.nucleus.ledger

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.stereotype.Component

@Aspect
@Component
class TransferRequestValidationAspect(
  val validators: List<TransferRequestValidator>,
) {
  @Around(
    "execution(* iterator.nucleus.ledger.LedgerEntryService.createTransfer(..)) && args(request)",
  )
  fun aroundCreateTransfer(
    pjp: ProceedingJoinPoint,
    request: CreateTransferRequest,
  ): Any {
    validators.forEach { it.validate(request) }
    return pjp.proceed()
  }
}
