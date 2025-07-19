package iterator.nucleus.account.feature.balancelimit

import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.aValidCustomerTranche
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.TestingFu.randomBigDecimal
import iterator.nucleus.account.AccountFeatureService
import iterator.nucleus.account.feature.FeatureConstants
import iterator.nucleus.ledger.CreateTransferRequest
import iterator.nucleus.ledger.LedgerConstants
import iterator.nucleus.ledger.LedgerEntryService
import iterator.nucleus.ledger.LedgerEntryType
import iterator.nucleus.parameter.ParameterValueService
import org.assertj.core.api.Assumptions.assumeThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.eq
import org.mockito.kotlin.given
import org.mockito.kotlin.verifyNoInteractions
import java.math.BigDecimal
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class BalanceLimitValidatorTest(
  @Mock val features: AccountFeatureService,
  @Mock val params: ParameterValueService,
  @Mock val ledger: LedgerEntryService,
) {
  lateinit var validator: BalanceLimitValidator

  @BeforeEach
  fun setup() {
    validator = BalanceLimitValidator(features, params, ledger)
  }

  @Test
  fun `should reject transfer that would cause balance limit to be exceeded`() {
    // given
    val cfg = BalanceLimitFeatureParameters(balanceLimit = randomBigDecimal(100000.00, 1000000.00))
    val balance = cfg.balanceLimit.minus(randomBigDecimal(1.00, 100.00))
    val amount = cfg.balanceLimit.minus(balance).add(BigDecimal.ONE)
    assumeThat(balance.add(amount)).isGreaterThan(cfg.balanceLimit)
    val account =
      aValidAccount(
        accountTemplate = aValidAccountTemplate(),
        customerTranche = aValidCustomerTranche(),
      )
    val request =
      CreateTransferRequest(
        fromAccount = aValidAccount(aValidAccountTemplate()),
        fromAddress = LedgerConstants.DEFAULT_ADDRESS,
        toAccount = account,
        toAddress = LedgerConstants.DEFAULT_ADDRESS,
        amount = amount,
        type = LedgerEntryType.DEPOSIT,
        timestamp = Instant.now(),
      )
    given {
      features.isFeatureEnabled(
        name = eq(FeatureConstants.BALANCE_LIMIT_FEATURE_NAME),
        account = eq(account),
      )
    }.willReturn(true)
    given {
      params.findAndBindEffectiveParameters(
        dataClass = eq(BalanceLimitFeatureParameters::class),
        effectiveAt = eq(request.timestamp),
        accountId = eq(account.accountId),
        accountTemplateId = eq(account.accountTemplate.accountTemplateId),
        customerTrancheId = eq(account.customerTranche!!.customerTrancheId),
      )
    }.willReturn(cfg)
    given {
      ledger.findCommittedBalance(
        accountId = eq(account.accountId),
        effectiveTimestamp = eq(request.timestamp),
        address = eq(LedgerConstants.DEFAULT_ADDRESS),
        asset = eq(LedgerConstants.DEFAULT_ASSET),
      )
    }.willReturn(balance)

    // when ... then
    assertThrows<IllegalArgumentException> { validator.validate(request) }
  }

  @Test
  fun `should ignore transfer to non-default address`() {
    // given
    val request =
      CreateTransferRequest(
        fromAccount = aValidAccount(aValidAccountTemplate()),
        fromAddress = LedgerConstants.DEFAULT_ADDRESS,
        toAccount = aValidAccount(aValidAccountTemplate()),
        toAddress = randomAlphanumeric(16),
        amount = randomBigDecimal(1.00, 100.00),
        type = LedgerEntryType.DEPOSIT,
        timestamp = Instant.now(),
      )

    // when ... then
    assertDoesNotThrow { validator.validate(request) }
    verifyNoInteractions(features, params, ledger)
  }

  @Test
  fun `should ignore transfer of non-default asset type`() {
    // given
    val request =
      CreateTransferRequest(
        fromAccount = aValidAccount(aValidAccountTemplate()),
        fromAddress = LedgerConstants.DEFAULT_ADDRESS,
        toAccount = aValidAccount(aValidAccountTemplate()),
        toAddress = LedgerConstants.DEFAULT_ADDRESS,
        amount = randomBigDecimal(1.00, 100.00),
        type = LedgerEntryType.DEPOSIT,
        timestamp = Instant.now(),
        asset = randomAlphanumeric(16),
      )

    // when ... then
    assertDoesNotThrow { validator.validate(request) }
    verifyNoInteractions(features, params, ledger)
  }

  @Test
  fun `should ignore interest application`() {
    // given
    val request =
      CreateTransferRequest(
        fromAccount = aValidAccount(aValidAccountTemplate()),
        fromAddress = LedgerConstants.DEFAULT_ADDRESS,
        toAccount = aValidAccount(aValidAccountTemplate()),
        toAddress = LedgerConstants.DEFAULT_ADDRESS,
        amount = randomBigDecimal(1.00, 100.00),
        type = LedgerEntryType.INTEREST_APPLICATION,
        timestamp = Instant.now(),
      )

    // when ... then
    assertDoesNotThrow { validator.validate(request) }
    verifyNoInteractions(features, params, ledger)
  }

  @Test
  fun `should ignore transfer to account that does not have the balance limit feature enabled`() {
    // given
    val cfg = BalanceLimitFeatureParameters(balanceLimit = randomBigDecimal(100000.00, 1000000.00))
    val balance = cfg.balanceLimit.minus(randomBigDecimal(1.00, 100.00))
    val amount = cfg.balanceLimit.minus(balance).add(BigDecimal.ONE)
    assumeThat(balance.add(amount)).isGreaterThan(cfg.balanceLimit)
    val account =
      aValidAccount(
        accountTemplate = aValidAccountTemplate(),
        customerTranche = aValidCustomerTranche(),
      )
    val request =
      CreateTransferRequest(
        fromAccount = aValidAccount(aValidAccountTemplate()),
        fromAddress = LedgerConstants.DEFAULT_ADDRESS,
        toAccount = account,
        toAddress = LedgerConstants.DEFAULT_ADDRESS,
        amount = amount,
        type = LedgerEntryType.DEPOSIT,
        timestamp = Instant.now(),
      )
    given {
      features.isFeatureEnabled(
        name = eq(FeatureConstants.BALANCE_LIMIT_FEATURE_NAME),
        account = eq(account),
      )
    }.willReturn(false)
    given {
      params.findAndBindEffectiveParameters(
        dataClass = eq(BalanceLimitFeatureParameters::class),
        effectiveAt = eq(request.timestamp),
        accountId = eq(account.accountId),
        accountTemplateId = eq(account.accountTemplate.accountTemplateId),
        customerTrancheId = eq(account.customerTranche!!.customerTrancheId),
      )
    }.willReturn(cfg)
    given {
      ledger.findCommittedBalance(
        accountId = eq(account.accountId),
        effectiveTimestamp = eq(request.timestamp),
        address = eq(LedgerConstants.DEFAULT_ADDRESS),
        asset = eq(LedgerConstants.DEFAULT_ASSET),
      )
    }.willReturn(balance)

    // when ... then
    assertDoesNotThrow { validator.validate(request) }
    verifyNoInteractions(params, ledger)
  }
}
