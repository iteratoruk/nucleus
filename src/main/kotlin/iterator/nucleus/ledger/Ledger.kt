package iterator.nucleus.ledger

import com.fasterxml.jackson.annotation.JsonIgnore
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
import org.springframework.kafka.core.KafkaTemplate
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
  ACCRUED_INTEREST_COALESCENCE,
  ON_US,
  WITHDRAWAL {
    override fun maybePublishSuccessfulOperation(
      kafka: KafkaTemplate<String, Any>,
      request: CreateTransferRequest,
      operationId: UUID,
    ) {
      kafka.send(
        LedgerTopics.WITHDRAWALS,
        WithdrawalMessage(
          accountId = request.fromAccount.accountId,
          operationId = operationId,
          timestamp = request.timestamp,
        ),
      )
    }
  },
  ;

  @JsonIgnore
  open fun maybePublishSuccessfulOperation(
    kafka: KafkaTemplate<String, Any>,
    request: CreateTransferRequest,
    operationId: UUID,
  ) {
    // do nothing
  }
}

@Service
class LedgerEntryService(
  val repo: LedgerEntryRepository,
  val kafka: KafkaTemplate<String, Any>,
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

  @Transactional
  fun createTransfer(request: CreateTransferRequest): List<LedgerEntry> {
    if (request.amount == BigDecimal.ZERO) {
      return emptyList()
    }
    require(request.amount > BigDecimal.ZERO) { "Amount must be positive." }
    val operationId = UUID.randomUUID()
    val entries =
      listOf(
        LedgerEntry(
          operationId = operationId,
          account = request.fromAccount,
          phase = LedgerEntryPhase.COMMITTED,
          amount = request.amount.negate(),
          type = request.type,
          address = request.fromAddress,
          timestamp = request.timestamp,
        ),
        LedgerEntry(
          operationId = operationId,
          account = request.toAccount,
          phase = LedgerEntryPhase.COMMITTED,
          amount = request.amount,
          type = request.type,
          address = request.toAddress,
          timestamp = request.timestamp,
        ),
      )
    val saved = repo.saveAll(entries)
    request.type.maybePublishSuccessfulOperation(kafka, request, operationId)
    return saved
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
