package iterator.nucleus.ledger

import iterator.nucleus.AbstractJpaRepositoryTest
import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.aValidLedgerEntry
import iterator.nucleus.TestingFu.randomAlphabetic
import iterator.nucleus.TestingFu.randomBigDecimal
import iterator.nucleus.toSevenDecimalPlaces
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.web.servlet.MockMvc
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

class LedgerEntryRepositoryTest
  @Autowired
  constructor(
    repo: LedgerEntryRepository,
    em: EntityManager,
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractJpaRepositoryTest<LedgerEntry, LedgerEntryRepository>(repo, em, ctx, mvc) {
    override fun randomValidEntity(): LedgerEntry {
      val accountTemplate = aValidAccountTemplate()
      val account = aValidAccount(accountTemplate)
      persistAndFlush(listOf(accountTemplate, account))
      return aValidLedgerEntry(account)
    }

    override fun entityClass(): Class<LedgerEntry> = LedgerEntry::class.java

    @Test
    fun `should find balances by account ID`() {
      // given
      val accountTemplate = aValidAccountTemplate()
      val account = aValidAccount(accountTemplate)
      val anotherAccount = aValidAccount(accountTemplate)
      persistAndFlush(listOf(accountTemplate, account, anotherAccount))
      val customAsset = "ZZZ_${randomAlphabetic(16).uppercase()}"
      val customAddress = "ZZZ_${randomAlphabetic(6).uppercase()}"
      // committed credits in the default address of default asset type
      val creditOne =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          type = LedgerEntryType.COMMITTED_CREDIT,
          amount = randomBigDecimal(10.00, 9999.99),
        )
      val anotherCredit =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          type = LedgerEntryType.COMMITTED_CREDIT,
          amount = randomBigDecimal(10.00, 9999.99),
        )
      // committed credit in the default address of custom asset type
      val creditTwo =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          type = LedgerEntryType.COMMITTED_CREDIT,
          asset = customAsset,
          amount = randomBigDecimal(10.00, 9999.99),
        )
      // a credit on another account: excluded
      val creditThree =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = anotherAccount,
          type = LedgerEntryType.COMMITTED_CREDIT,
          amount = randomBigDecimal(10.00, 9999.99),
        )
      // a committed credit in another address of default asset type
      val creditFour =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          address = customAddress,
          type = LedgerEntryType.COMMITTED_CREDIT,
          amount = randomBigDecimal(10.00, 9999.99),
        )
      // a pending credit in the default address of default asset type
      val pendingCreditOne =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          type = LedgerEntryType.PENDING_CREDIT,
          amount = randomBigDecimal(10.00, 9999.99),
        )
      // a pending credit in the default address of custom asset type
      val pendingCreditTwo =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          type = LedgerEntryType.PENDING_CREDIT,
          asset = customAsset,
          amount = randomBigDecimal(10.00, 9999.99),
        )
      // a committed debit in the default address of default asset type
      val debitOne =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          type = LedgerEntryType.COMMITTED_DEBIT,
          amount = creditOne.amount.divide(2.toBigDecimal(), RoundingMode.HALF_EVEN),
        )
      // a committed debit in the default address of custom asset type
      val debitTwo =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          type = LedgerEntryType.COMMITTED_DEBIT,
          asset = customAsset,
          amount = creditTwo.amount.divide(2.toBigDecimal(), RoundingMode.HALF_EVEN),
        )
      // a pending debit in the default address of default asset type
      val pendingDebitOne =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          type = LedgerEntryType.PENDING_DEBIT,
          amount = creditOne.amount.divide(2.toBigDecimal(), RoundingMode.HALF_EVEN),
        )
      // a pending debit in the default address of custom asset type
      val pendingDebitTwo =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          type = LedgerEntryType.PENDING_DEBIT,
          asset = customAsset,
          amount = creditTwo.amount.divide(2.toBigDecimal(), RoundingMode.HALF_EVEN),
        )
      persistAndFlush(
        listOf(
          creditOne,
          anotherCredit,
          creditTwo,
          creditThree,
          creditFour,
          debitOne,
          debitTwo,
          pendingCreditOne,
          pendingCreditTwo,
          pendingDebitOne,
          pendingDebitTwo,
        ),
      )

      // when
      val actual = repo.findBalancesByAccount(account.accountId)

      // then
      val expected =
        listOf(
          ExpectedBalance(
            address = LedgerConstants.DEFAULT_ADDRESS,
            asset = LedgerConstants.DEFAULT_ASSET,
            committedBalance =
              creditOne.amount
                .add(anotherCredit.amount)
                .minus(debitOne.amount)
                .toSevenDecimalPlaces(),
            pendingBalance =
              pendingCreditOne.amount.minus(pendingDebitOne.amount).toSevenDecimalPlaces(),
          ),
          ExpectedBalance(
            address = LedgerConstants.DEFAULT_ADDRESS,
            asset = customAsset,
            committedBalance = creditTwo.amount.minus(debitTwo.amount).toSevenDecimalPlaces(),
            pendingBalance =
              pendingCreditTwo.amount.minus(pendingDebitTwo.amount).toSevenDecimalPlaces(),
          ),
          ExpectedBalance(
            address = customAddress,
            asset = LedgerConstants.DEFAULT_ASSET,
            committedBalance = creditFour.amount.toSevenDecimalPlaces(),
            pendingBalance = BigDecimal.ZERO,
          ),
        )
      assertThat(actual.map { ExpectedBalance.fromInterface(it) }).hasSameElementsAs(expected)
    }

    @Test
    fun `should not be able to update ledger entry`() {
      // given
      val entry = randomValidEntity()
      persistAndFlush(entry)
      entry.amount = randomBigDecimal(10.00, 9999.99)

      // when
      assertThrows<UnsupportedOperationException> { repo.saveAndFlush(entry) }
    }
  }

data class ExpectedBalance(
  override val address: String,
  override val asset: String,
  override val committedBalance: BigDecimal,
  override val pendingBalance: BigDecimal,
) : Balance {
  companion object {
    fun fromInterface(balance: Balance): ExpectedBalance =
      ExpectedBalance(
        balance.address,
        balance.asset,
        balance.committedBalance,
        balance.pendingBalance,
      )
  }
}
