package iterator.nucleus.ledger

import iterator.nucleus.AbstractApiTest
import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.EntityManagerHelper
import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountFeature
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.randomBigDecimal
import iterator.nucleus.account.feature.FeatureConstants
import iterator.nucleus.parameter.ParameterDefinition
import iterator.nucleus.parameter.ParameterLevel
import iterator.nucleus.parameter.ParameterValue
import iterator.nucleus.truncatedToPostgresAccuracy
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.assertj.core.api.Assumptions.assumeThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.annotation.Rollback
import org.springframework.test.web.servlet.MockMvc
import java.math.BigDecimal
import java.time.Instant

@Rollback
@Transactional
@AutoConfigureTestEntityManager
class LedgerValidationAspectTest
  @Autowired
  constructor(
    ctx: GenericApplicationContext,
    mvc: MockMvc,
    override val em: EntityManager,
  ) : AbstractApiTest(ctx, mvc),
    EntityManagerHelper<AbstractJpaEntity> {
    override fun entityClass(): Class<AbstractJpaEntity> = AbstractJpaEntity::class.java

    @Autowired lateinit var ledgerEntryService: LedgerEntryService

    @Test
    fun `ledger validation aspect should prevent balance limit breach for account with balance limit feature`() {
      // given
      val now = Instant.now().truncatedToPostgresAccuracy()
      val accountTemplate = aValidAccountTemplate()
      persist(accountTemplate)
      val feature = aValidAccountFeature(name = FeatureConstants.BALANCE_LIMIT_FEATURE_NAME)
      persist(feature)
      val fromAccount = aValidAccount(accountTemplate)
      val toAccount = aValidAccount(accountTemplate)
      persist(listOf(fromAccount, toAccount))
      toAccount.features.add(feature)
      persist(toAccount)
      val def = ParameterDefinition(name = "balanceLimit")
      persist(def)
      val limit = randomBigDecimal(100000.00, 1000000.00)
      val value =
        ParameterValue(
          definition = def,
          value = limit.toPlainString(),
          level = ParameterLevel.ACCOUNT_TEMPLATE,
          resourceId = accountTemplate.accountTemplateId,
          effectiveFrom = now.minusSeconds(1),
        )
      persist(value)
      flush()
      val balance = limit.minus(randomBigDecimal(1.00, 100.00))
      val amount = limit.minus(balance).add(BigDecimal.ONE)
      assumeThat(balance.add(amount)).isGreaterThan(limit)
      // create ledger entries to establish the balance
      ledgerEntryService.createTransfer(
        CreateTransferRequest(
          fromAccount = fromAccount,
          fromAddress = LedgerConstants.DEFAULT_ADDRESS,
          toAccount = toAccount,
          toAddress = LedgerConstants.DEFAULT_ADDRESS,
          amount = balance,
          type = LedgerEntryType.DEPOSIT,
          timestamp = now,
        ),
      )

      // when ... then
      assertThrows<IllegalArgumentException> {
        ledgerEntryService.createTransfer(
          CreateTransferRequest(
            fromAccount = fromAccount,
            fromAddress = LedgerConstants.DEFAULT_ADDRESS,
            toAccount = toAccount,
            toAddress = LedgerConstants.DEFAULT_ADDRESS,
            amount = amount,
            type = LedgerEntryType.DEPOSIT,
            timestamp = now,
          ),
        )
      }
    }
  }
