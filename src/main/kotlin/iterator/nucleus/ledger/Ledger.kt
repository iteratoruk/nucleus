package iterator.nucleus.ledger

import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.account.Account
import iterator.nucleus.ledger.LedgerConstants.DEFAULT_ADDRESS
import iterator.nucleus.ledger.LedgerConstants.DEFAULT_ASSET
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
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
  var address: String = DEFAULT_ADDRESS,
  var asset: String = DEFAULT_ASSET,
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

@Repository interface LedgerEntryRepository : AbstractJpaRepository<LedgerEntry>
