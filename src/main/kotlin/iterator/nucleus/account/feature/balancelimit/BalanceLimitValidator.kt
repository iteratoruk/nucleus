package iterator.nucleus.account.feature.balancelimit

import iterator.nucleus.account.AccountFeatureService
import iterator.nucleus.account.feature.FeatureConstants
import iterator.nucleus.ledger.CreateTransferRequest
import iterator.nucleus.ledger.LedgerConstants
import iterator.nucleus.ledger.LedgerEntryService
import iterator.nucleus.ledger.LedgerEntryType
import iterator.nucleus.ledger.TransferRequestValidator
import iterator.nucleus.parameter.ParameterValueService
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class BalanceLimitValidator(
  val features: AccountFeatureService,
  val params: ParameterValueService,
  val ledger: LedgerEntryService,
) : TransferRequestValidator {
  override fun validate(request: CreateTransferRequest) {
    if (isBalanceLimitExempt(request)) {
      return
    }
    if (!features.isFeatureEnabled(
        FeatureConstants.BALANCE_LIMIT_FEATURE_NAME,
        request.toAccount,
      )
    ) {
      return
    }
    val cfg =
      params.findAndBindEffectiveParameters(
        dataClass = BalanceLimitFeatureParameters::class,
        effectiveAt = request.timestamp,
        accountId = request.toAccount.accountId,
        accountTemplateId = request.toAccount.accountTemplate.accountTemplateId,
        customerTrancheId = request.toAccount.customerTranche?.customerTrancheId,
      )
    val balance =
      ledger.findCommittedBalance(
        accountId = request.toAccount.accountId,
        effectiveTimestamp = request.timestamp,
      )
    require(balance.add(request.amount) <= cfg.balanceLimit) {
      "Transfer would cause balance limit to be exceeded"
    }
  }

  private fun isBalanceLimitExempt(request: CreateTransferRequest): Boolean =
    request.toAddress != LedgerConstants.DEFAULT_ADDRESS ||
      request.asset != LedgerConstants.DEFAULT_ASSET ||
      request.type == LedgerEntryType.INTEREST_APPLICATION
}

data class BalanceLimitFeatureParameters(
  val balanceLimit: BigDecimal,
)
