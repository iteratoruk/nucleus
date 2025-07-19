package iterator.nucleus.ledger

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ZeroAmountTransferRequestValidator : TransferRequestValidator {
  override fun validate(request: CreateTransferRequest) {
    require(request.amount > BigDecimal.ZERO) { "Amount must be positive." }
  }
}
