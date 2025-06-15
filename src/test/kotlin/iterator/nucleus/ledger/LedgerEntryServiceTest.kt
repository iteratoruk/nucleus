package iterator.nucleus.ledger

import iterator.nucleus.TestingFu.randomAlphabetic
import iterator.nucleus.TestingFu.randomBigDecimal
import iterator.nucleus.account.Account
import iterator.nucleus.account.AccountService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.given
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class LedgerEntryServiceTest(
  @Mock val repo: LedgerEntryRepository,
  @Mock val accounts: AccountService,
) {
  val service = LedgerEntryService(repo)

  @Test
  fun `createTransfer should return empty list when amount is zero`() {
    // when
    val result =
      service.createTransfer(
        fromAccount = mock(),
        fromAddress = "A",
        toAccount = mock(),
        toAddress = "B",
        amount = BigDecimal.ZERO,
        timestamp = Instant.now(),
      )
    // then
    assertEquals(emptyList(), result)
    // and repo.saveAll should never be called
    verify(repo, never()).saveAll(any<Iterable<LedgerEntry>>())
  }

  @Test
  fun `createTransfer should throw if amount negative`() {
    // when / then
    assertThrows<IllegalArgumentException> {
      service.createTransfer(
        fromAccount = mock(),
        fromAddress = "A",
        toAccount = mock(),
        toAddress = "B",
        amount = BigDecimal("-10.00"),
        timestamp = Instant.now(),
      )
    }
    verify(repo, never()).saveAll(any<Iterable<LedgerEntry>>())
  }

  @Test
  fun `createTransfer should save two entries with correct signs`() {
    // given
    val fromAccount: Account = mock()
    val toAccount: Account = mock()
    val amount = BigDecimal("100.00")
    val now = Instant.now()

    // Simulate repo.saveAll(...) returning exactly the list it is given
    given { repo.saveAll(any<Iterable<LedgerEntry>>()) }
      .willAnswer { invocation -> invocation.getArgument(0) as List<LedgerEntry> }

    // when
    val result =
      service.createTransfer(
        fromAccount = fromAccount,
        fromAddress = "ADDR1",
        toAccount = toAccount,
        toAddress = "ADDR2",
        amount = amount,
        timestamp = now,
      )

    // then: we expect exactly two entries
    assertEquals(2, result.size)
    val debit = result.first { it.account == fromAccount }
    val credit = result.first { it.account == toAccount }

    // Debit should be negative and credit positive
    assertEquals(amount.negate(), debit.amount)
    assertEquals(amount, credit.amount)

    // OperationId should be same for both
    assertEquals(debit.operationId, credit.operationId)

    // Phase should be COMMITTED and timestamp, address set correctly
    assertEquals(LedgerEntryPhase.COMMITTED, debit.phase)
    assertEquals("ADDR1", debit.address)
    assertEquals(now, debit.timestamp)

    assertEquals(LedgerEntryPhase.COMMITTED, credit.phase)
    assertEquals("ADDR2", credit.address)
    assertEquals(now, credit.timestamp)

    // verify repo.saveAll was called with exactly this list
    verify(repo).saveAll(eq(result))
  }

  @Test
  fun `reverseOperation should reverse exactly two entries`() {
    // given
    val opId = UUID.randomUUID()
    val now = Instant.now()

    // Two original entries
    val originalDebit =
      LedgerEntry(
        operationId = opId,
        account = mock(),
        phase = LedgerEntryPhase.COMMITTED,
        amount = BigDecimal("-50.00"),
        address = "A",
        asset = "X",
        timestamp = now.minusSeconds(60),
        reversedEntry = null,
      )
    val originalCredit =
      LedgerEntry(
        operationId = opId,
        account = mock(),
        phase = LedgerEntryPhase.COMMITTED,
        amount = BigDecimal("50.00"),
        address = "A",
        asset = "X",
        timestamp = now.minusSeconds(60),
        reversedEntry = null,
      )

    given { repo.findByOperationId(eq(opId)) }.willReturn(listOf(originalDebit, originalCredit))
    // Simulate saveAll returning exactly what we pass in
    given { repo.saveAll(any<Iterable<LedgerEntry>>()) }
      .willAnswer { it.getArgument(0) as List<LedgerEntry> }

    // when
    val reversals = service.reverseOperation(opId, now)

    // then: exactly two reversal entries
    assertEquals(2, reversals.size)
    // Each reversal should flip the sign and set reversedEntry
    reversals.forEach { reversal ->
      val matchingOriginal =
        when (reversal.amount.signum()) {
          1 -> originalDebit // if reversed amount is positive, that matches original debit
          -1 -> originalCredit // if reversed amount is negative, that matches original credit
          else -> error("Unexpected zero amount in reversal")
        }
      // Check reversedEntry is set
      assertEquals(matchingOriginal, reversal.reversedEntry)
      // Check sign is flipped
      assertEquals(matchingOriginal.amount.negate(), reversal.amount)
      // Same operationId
      assertEquals(opId, reversal.operationId)
      // Same phase
      assertEquals(matchingOriginal.phase, reversal.phase)
      // Same address + asset
      assertEquals(matchingOriginal.address, reversal.address)
      assertEquals(matchingOriginal.asset, reversal.asset)
      // Timestamp is the 'now' provided
      assertEquals(now, reversal.timestamp)
    }

    // verify repo.saveAll was called once
    verify(repo).saveAll(eq(reversals))
  }

  @Test
  fun `reverseOperation should throw when not exactly two originals found`() {
    // given: no entries
    val opId = UUID.randomUUID()
    given { repo.findByOperationId(eq(opId)) }.willReturn(emptyList())

    // when/then
    assertThrows<IllegalStateException> {
      // check(...) throws IllegalStateException
      service.reverseOperation(opId)
    }
    verify(repo, never()).saveAll(any<Iterable<LedgerEntry>>())
  }

  @Test
  fun `reverseOperation should throw when original already reversed`() {
    // given: two originals, but one has reversedEntry != null
    val opId = UUID.randomUUID()
    val original1 =
      LedgerEntry(
        operationId = opId,
        account = mock(),
        phase = LedgerEntryPhase.COMMITTED,
        amount = BigDecimal("20.00"),
        address = "A",
        asset = "X",
        timestamp = Instant.now(),
        reversedEntry =
          LedgerEntry(
            operationId = opId,
            account = mock(),
            phase = LedgerEntryPhase.COMMITTED,
            amount = BigDecimal("-20.00"),
            address = "A",
            asset = "X",
            timestamp = Instant.now(),
          ),
      )
    val original2 =
      LedgerEntry(
        operationId = opId,
        account = mock(),
        phase = LedgerEntryPhase.COMMITTED,
        amount = BigDecimal("-20.00"),
        address = "A",
        asset = "X",
        timestamp = Instant.now(),
        reversedEntry = null,
      )
    given { repo.findByOperationId(eq(opId)) }.willReturn(listOf(original1, original2))

    // when/then
    assertThrows<IllegalArgumentException> { service.reverseOperation(opId) }
    verify(repo, never()).saveAll(any<Iterable<LedgerEntry>>())
  }

  @Test
  fun `should find committed balances for given addresses when find committed balances`() {
    // given
    val accountId = UUID.randomUUID()
    val effectiveTimestamp = Instant.now()
    val addr1 = randomAlphabetic(8).uppercase()
    val addr2 = randomAlphabetic(8).uppercase()
    val addr3 = randomAlphabetic(8).uppercase()
    val addresses = setOf(addr1, addr2, addr3)
    val asset = randomAlphabetic(8).uppercase()
    val balances =
      listOf(
        // in the result
        expectedBalance(address = addr1, asset = asset),
        // a random one just to prove service function behaviour (wouldn't exist in result,
        // because query already groups and sums already)
        expectedBalance(address = addr1, asset = asset),
        // filtered out: random address
        expectedBalance(asset = asset),
        // filtered out: random asset
        expectedBalance(address = addr1),
        // in the result
        expectedBalance(address = addr2, asset = asset),
        expectedBalance(address = addr3, asset = asset),
      )
    given {
      repo.findBalancesByAccount(accountId = eq(accountId), timestamp = eq(effectiveTimestamp))
    }.willReturn(balances)

    // when
    val actual =
      service.findCommittedBalances(
        accountId = accountId,
        effectiveTimestamp = effectiveTimestamp,
        addresses = addresses,
        asset = asset,
      )

    // then
    val expected =
      mapOf(
        addr1 to balances[0].committedBalance.add(balances[1].committedBalance),
        addr2 to balances[4].committedBalance,
        addr3 to balances[5].committedBalance,
      )
    assertThat(actual).isEqualTo(expected)
  }

  @Test
  fun `should find committed balance for the given address when find committed balance`() {
    // given
    val accountId = UUID.randomUUID()
    val effectiveTimestamp = Instant.now()
    val addr = randomAlphabetic(8).uppercase()
    val asset = randomAlphabetic(8).uppercase()
    val balances =
      listOf(
        expectedBalance(address = addr, asset = asset),
        expectedBalance(address = addr, asset = asset),
      )
    given {
      repo.findBalancesByAccount(accountId = eq(accountId), timestamp = eq(effectiveTimestamp))
    }.willReturn(balances)

    // when
    val actual = service.findCommittedBalance(accountId, effectiveTimestamp, addr, asset)

    // then
    assertThat(actual).isEqualTo(balances.sumOf { it.committedBalance })
  }

  private fun expectedBalance(
    address: String = randomAlphabetic(8).uppercase(),
    asset: String = randomAlphabetic(8).uppercase(),
    committedBalance: BigDecimal = randomBigDecimal(10.00, 100.00),
    pendingBalance: BigDecimal = randomBigDecimal(10.00, 100.00).negate(),
  ): ExpectedBalance =
    ExpectedBalance(
      address = address,
      asset = asset,
      committedBalance = committedBalance,
      pendingBalance = pendingBalance,
    )
}
