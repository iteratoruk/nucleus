package iterator.nucleus.ledger

import iterator.nucleus.AbstractJpaRepositoryTest
import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.aValidLedgerEntry
import iterator.nucleus.TestingFu.randomAlphabetic
import iterator.nucleus.TestingFu.randomBigDecimal
import iterator.nucleus.TestingFu.randomEnum
import iterator.nucleus.toSevenDecimalPlaces
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.web.servlet.MockMvc
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
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
          phase = LedgerEntryPhase.COMMITTED,
          amount = randomBigDecimal(10.00, 9999.99),
          type = randomEnum(),
        )
      val anotherCredit =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          phase = LedgerEntryPhase.COMMITTED,
          amount = randomBigDecimal(10.00, 9999.99),
          type = randomEnum(),
        )
      // committed credit in the default address of custom asset type
      val creditTwo =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          phase = LedgerEntryPhase.COMMITTED,
          asset = customAsset,
          amount = randomBigDecimal(10.00, 9999.99),
          type = randomEnum(),
        )
      // a credit on another account: excluded
      val creditThree =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = anotherAccount,
          phase = LedgerEntryPhase.COMMITTED,
          amount = randomBigDecimal(10.00, 9999.99),
          type = randomEnum(),
        )
      // a committed credit in another address of default asset type
      val creditFour =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          address = customAddress,
          phase = LedgerEntryPhase.COMMITTED,
          amount = randomBigDecimal(10.00, 9999.99),
          type = randomEnum(),
        )
      // a pending credit in the default address of default asset type
      val pendingCreditOne =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          phase = LedgerEntryPhase.PENDING,
          amount = randomBigDecimal(10.00, 9999.99),
          type = randomEnum(),
        )
      // a pending credit in the default address of custom asset type
      val pendingCreditTwo =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          phase = LedgerEntryPhase.PENDING,
          asset = customAsset,
          amount = randomBigDecimal(10.00, 9999.99),
          type = randomEnum(),
        )
      // a committed debit in the default address of default asset type
      val debitOne =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          phase = LedgerEntryPhase.COMMITTED,
          amount = creditOne.amount.divide(2.toBigDecimal(), RoundingMode.HALF_EVEN).negate(),
          type = randomEnum(),
        )
      // a committed debit in the default address of custom asset type
      val debitTwo =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          phase = LedgerEntryPhase.COMMITTED,
          asset = customAsset,
          amount = creditTwo.amount.divide(2.toBigDecimal(), RoundingMode.HALF_EVEN).negate(),
          type = randomEnum(),
        )
      // a pending debit in the default address of default asset type
      val pendingDebitOne =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          phase = LedgerEntryPhase.PENDING,
          amount = creditOne.amount.divide(2.toBigDecimal(), RoundingMode.HALF_EVEN).negate(),
          type = randomEnum(),
        )
      // a pending debit in the default address of custom asset type
      val pendingDebitTwo =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          phase = LedgerEntryPhase.PENDING,
          asset = customAsset,
          amount = creditTwo.amount.divide(2.toBigDecimal(), RoundingMode.HALF_EVEN).negate(),
          type = randomEnum(),
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
                .minus(debitOne.amount.abs())
                .toSevenDecimalPlaces(),
            pendingBalance =
              pendingCreditOne.amount
                .minus(pendingDebitOne.amount.abs())
                .toSevenDecimalPlaces(),
          ),
          ExpectedBalance(
            address = LedgerConstants.DEFAULT_ADDRESS,
            asset = customAsset,
            committedBalance =
              creditTwo.amount.minus(debitTwo.amount.abs()).toSevenDecimalPlaces(),
            pendingBalance =
              pendingCreditTwo.amount
                .minus(pendingDebitTwo.amount.abs())
                .toSevenDecimalPlaces(),
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
    fun `should not update ledger entry when saving`() {
      // given
      val entry = randomValidEntity()
      persistAndFlush(entry)
      val originalAmount = entry.amount
      entry.amount = randomBigDecimal(10.00, 9999.99)

      // when
      repo.saveAndFlush(entry)
      clear()
      val reloaded = repo.findById(entry.id).get()

      // then
      assertThat(reloaded.amount).isEqualTo(originalAmount)
    }

    @Test
    fun `should find entries by operation ID`() {
      // given
      val accountTemplate = aValidAccountTemplate()
      val account = aValidAccount(accountTemplate)
      persistAndFlush(listOf(accountTemplate, account))

      val opId = UUID.randomUUID()
      // Create two entries sharing the same operationId
      val e1 =
        LedgerEntry(
          operationId = opId,
          account = account,
          phase = LedgerEntryPhase.COMMITTED,
          amount = BigDecimal("100.00"),
          type = randomEnum(),
        )
      val e2 =
        LedgerEntry(
          operationId = opId,
          account = account,
          phase = LedgerEntryPhase.COMMITTED,
          amount = BigDecimal("-50.00"),
          type = randomEnum(),
        )
      // A third entry under a different operationId
      val e3 = aValidLedgerEntry(account)
      persistAndFlush(listOf(e1, e2, e3))

      // when
      val found = repo.findByOperationId(opId)

      // then
      assertThat(found).hasSize(2)
      assertThat(found.map { it.id }).containsExactlyInAnyOrder(e1.id, e2.id)
    }

    @Test
    fun `findBalancesByAccount should respect timestamp filter`() {
      // given
      val accountTemplate = aValidAccountTemplate()
      val account = aValidAccount(accountTemplate)
      persistAndFlush(listOf(accountTemplate, account))

      val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
      val earlier = now.minusSeconds(60)
      val later = now.plusSeconds(60)

      // Debit and credit before 'now'
      val beforeCredit =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          phase = LedgerEntryPhase.COMMITTED,
          amount = BigDecimal("100.00"),
          timestamp = earlier,
          type = randomEnum(),
        )
      val beforeDebit =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          phase = LedgerEntryPhase.COMMITTED,
          amount = BigDecimal("-30.00"),
          timestamp = earlier,
          type = randomEnum(),
        )
      // This entry is after 'now' and should be excluded
      val afterCredit =
        LedgerEntry(
          operationId = UUID.randomUUID(),
          account = account,
          phase = LedgerEntryPhase.COMMITTED,
          amount = BigDecimal("200.00"),
          timestamp = later,
          type = randomEnum(),
        )
      persistAndFlush(listOf(beforeCredit, beforeDebit, afterCredit))

      // when
      val balances = repo.findBalancesByAccount(account.accountId, now)

      // then
      // Only beforeCredit (+100) and beforeDebit (-30) should be summed
      val expectedBalance = (BigDecimal("100.00").minus(BigDecimal("30.00"))).toSevenDecimalPlaces()
      val expected =
        listOf(
          ExpectedBalance(
            address = LedgerConstants.DEFAULT_ADDRESS,
            asset = LedgerConstants.DEFAULT_ASSET,
            committedBalance = expectedBalance,
            pendingBalance = BigDecimal.ZERO,
          ),
        )
      assertThat(balances.map { ExpectedBalance.fromInterface(it) }).hasSameElementsAs(expected)
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
