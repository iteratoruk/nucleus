package iterator.nucleus.ledger

import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.account.Account
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Cache(region = "ledger-entries", usage = CacheConcurrencyStrategy.READ_ONLY)
class LedgerEntry(
  var operationId: UUID,
  @ManyToOne var account: Account,
  @Enumerated(EnumType.STRING) var type: LedgerEntryType,
  var amount: BigDecimal,
  var address: String = LedgerConstants.DEFAULT_ADDRESS,
  var asset: String = LedgerConstants.DEFAULT_ASSET,
  var timestamp: Instant = Instant.now(),
  @OneToOne var reversedEntry: LedgerEntry? = null,
) : AbstractJpaEntity()

enum class LedgerEntryType {
  COMMITTED_CREDIT,
  COMMITTED_DEBIT,
  PENDING_CREDIT,
  PENDING_DEBIT,
  REVERSAL_CREDIT,
  REVERSAL_DEBIT,
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
          e.address AS address,
          e.asset   AS asset,
          SUM(
            CASE
              WHEN e.type = 'COMMITTED_CREDIT'   OR e.type = 'REVERSAL_DEBIT'  THEN e.amount
              WHEN e.type = 'COMMITTED_DEBIT'    OR e.type = 'REVERSAL_CREDIT' THEN -e.amount
              ELSE 0
            END
          ) AS committedBalance,
          SUM(
            CASE
              WHEN e.type = 'PENDING_CREDIT' THEN e.amount
              WHEN e.type = 'PENDING_DEBIT'  THEN -e.amount
              ELSE 0
            END
          ) AS pendingBalance
        FROM LedgerEntry e
        WHERE e.account.accountId = :accountId
        GROUP BY e.address, e.asset
        ORDER BY e.address, e.asset
        """,
  )
  fun findBalancesByAccount(
    @Param("accountId") accountId: UUID,
  ): List<Balance>
}

interface Balance {
  val address: String
  val asset: String
  val committedBalance: BigDecimal
  val pendingBalance: BigDecimal
}
