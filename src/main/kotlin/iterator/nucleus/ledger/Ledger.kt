package iterator.nucleus.ledger

import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.account.Account
import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Immutable
@Cache(region = "ledger-entries", usage = CacheConcurrencyStrategy.READ_ONLY)
class LedgerEntry(
  var operationId: UUID,
  @ManyToOne var account: Account,
  @Enumerated(EnumType.STRING) var phase: LedgerEntryPhase,
  var amount: BigDecimal,
  @Enumerated(EnumType.STRING) var type: LedgerEntryType,
  var address: String = LedgerConstants.DEFAULT_ADDRESS,
  var asset: String = LedgerConstants.DEFAULT_ASSET,
  var timestamp: Instant = Instant.now(),
  @OneToOne(cascade = [CascadeType.PERSIST]) var reversedEntry: LedgerEntry? = null,
) : AbstractJpaEntity()

enum class LedgerEntryType {
  INTEREST_ACCRUAL,
  BONUS_INTEREST_ACCRUAL,
  ACCRUED_INTEREST_ROUNDING_SETTLEMENT,
  INTEREST_APPLICATION,
  REVERSAL,
  TRANSFER,
}

@Service
class LedgerEntryService(
  val repo: LedgerEntryRepository,
) {
  fun findCommittedBalance(
    accountId: UUID,
    effectiveTimestamp: Instant = Instant.now(),
    address: String = LedgerConstants.DEFAULT_ADDRESS,
    asset: String = LedgerConstants.DEFAULT_ASSET,
  ): BigDecimal =
    repo
      .findBalancesByAccount(accountId, effectiveTimestamp)
      .filter { address == it.address && asset == it.asset }
      .sumOf { it.committedBalance }

  fun findCommittedBalances(
    accountId: UUID,
    effectiveTimestamp: Instant = Instant.now(),
    addresses: Set<String> = setOf(LedgerConstants.DEFAULT_ADDRESS),
    asset: String = LedgerConstants.DEFAULT_ASSET,
  ): Map<String, BigDecimal> =
    repo
      .findBalancesByAccount(accountId, effectiveTimestamp)
      .filter { addresses.contains(it.address) && asset == it.asset }
      .groupBy { it.address }
      .mapValues { entry -> entry.value.sumOf { it.committedBalance } }

  @Suppress("LongParameterList")
  @Transactional
  fun createTransfer(
    fromAccount: Account,
    fromAddress: String,
    toAccount: Account,
    toAddress: String,
    amount: BigDecimal,
    type: LedgerEntryType,
    timestamp: Instant,
  ): List<LedgerEntry> {
    if (amount == BigDecimal.ZERO) {
      return emptyList()
    }
    require(amount > BigDecimal.ZERO) { "Amount must be positive." }
    val operationId = UUID.randomUUID()
    val entries =
      listOf(
        LedgerEntry(
          operationId = operationId,
          account = fromAccount,
          phase = LedgerEntryPhase.COMMITTED,
          amount = amount.negate(),
          type = type,
          address = fromAddress,
          timestamp = timestamp,
        ),
        LedgerEntry(
          operationId = operationId,
          account = toAccount,
          phase = LedgerEntryPhase.COMMITTED,
          amount = amount,
          type = type,
          address = toAddress,
          timestamp = timestamp,
        ),
      )
    return repo.saveAll(entries)
  }

  @Transactional
  fun reverseOperation(
    operationId: UUID,
    timestamp: Instant = Instant.now(),
  ): List<LedgerEntry> {
    val operation = repo.findByOperationId(operationId)
    check(operation.size == 2) {
      "Original operation $operationId does not consist of exactly 2 entries (found ${operation.size})."
    }
    require(operation.all { it.reversedEntry == null }) {
      "Operation $operationId has already been reversed."
    }
    val reversals =
      operation.map {
        LedgerEntry(
          operationId = it.operationId,
          account = it.account,
          phase = it.phase,
          amount = it.amount.negate(),
          type = LedgerEntryType.REVERSAL,
          address = it.address,
          asset = it.asset,
          timestamp = timestamp,
          reversedEntry = it,
        )
      }
    return repo.saveAll(reversals)
  }
}

data class CreateTransferRequest(
  val fromAccount: Account,
  val fromAddress: String,
  val toAccount: Account,
  val toAddress: String,
  val amount: BigDecimal,
  val type: LedgerEntryType,
  val timestamp: Instant,
)

enum class LedgerEntryPhase {
  COMMITTED,
  COMMITTED_DEBIT,
  PENDING,
  PENDING_DEBIT,
}

object LedgerConstants {
  const val DEFAULT_ADDRESS = "DEFAULT"
  const val DEFAULT_ASSET = "COMMERCIAL_BANK_MONEY"
}

@Repository
interface LedgerEntryRepository : AbstractJpaRepository<LedgerEntry> {
  @Query(
    """
      SELECT
        e.address        AS address,
        e.asset          AS asset,
        SUM(
          CASE
            WHEN e.phase = 'COMMITTED' THEN e.amount
            ELSE 0
          END
        ) AS committedBalance,
        SUM(
          CASE
            WHEN e.phase = 'PENDING' THEN e.amount
            ELSE 0
          END
        ) AS pendingBalance
      FROM LedgerEntry e
      WHERE e.account.accountId = :accountId
        AND e.timestamp <= :timestamp
      GROUP BY e.address, e.asset
      ORDER BY e.address, e.asset
    """,
  )
  fun findBalancesByAccount(
    @Param("accountId") accountId: UUID,
    @Param("timestamp") timestamp: Instant = Instant.now(),
  ): List<Balance>

  fun findByOperationId(operationId: UUID): List<LedgerEntry>
}

interface Balance {
  val address: String
  val asset: String
  val committedBalance: BigDecimal
  val pendingBalance: BigDecimal
}
