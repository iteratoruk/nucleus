package iterator.nucleus.account

import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.AbstractMutableJpaEntity
import iterator.nucleus.account.template.AccountTemplate
import iterator.nucleus.customer.CustomerTranche
import jakarta.persistence.CascadeType
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.util.UUID

@Entity
@Cache(region = "accounts", usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
class Account(
  var accountId: UUID,
  var customerId: String,
  @Enumerated(EnumType.STRING) var status: AccountStatus,
  var internal: Boolean = false,
  @Enumerated(EnumType.STRING) var internalAccountRole: InternalAccountRole? = null,
  @ManyToOne var accountTemplate: AccountTemplate,
  @ManyToOne var customerTranche: CustomerTranche? = null,
  @ManyToMany(cascade = [CascadeType.PERSIST, CascadeType.MERGE])
  var features: MutableSet<AccountFeature> = mutableSetOf(),
) : AbstractMutableJpaEntity()

@Service
class AccountService(
  val repo: AccountRepository,
) {
  fun findRequiredAccountPair(
    firstAccountId: UUID,
    secondAccountId: UUID,
  ): Pair<Account, Account> {
    val first = repo.findByAccountId(firstAccountId)
    val second = repo.findByAccountId(secondAccountId)
    requireNotNull(first) { "Account with id $firstAccountId does not exist." }
    requireNotNull(second) { "Account with id $secondAccountId does not exist." }
    return Pair(first, second)
  }

  fun findRequiredOpenAccount(accountId: UUID): Account {
    val account = repo.findByAccountIdAndStatus(accountId, AccountStatus.OPEN)
    requireNotNull(account) { "Account with id $accountId does not exist." }
    return account
  }

  fun findRequiredInternalAccount(internalAccountRole: InternalAccountRole): Account {
    val account =
      repo.findByInternalIsTrueAndCustomerIdAndInternalAccountRole(
        customerId = AccountConstants.INTERNAL_BANK_CUSTOMER_ID,
        internalAccountRole = internalAccountRole,
      )
    requireNotNull(account) { "Internal account with role $internalAccountRole does not exist." }
    return account
  }
}

enum class InternalAccountRole {
  CREDIT_PAYMENT_RECOVERY,
  PAYMENT_EXCEPTION_HANDLING,
  STAND_IN_RETURNS,
  FRAUD,
  PAYMENT_OPERATIONS_HOLDINGS,
  PROFIT_AND_LOSS,
  REDRESS,
  WRITE_OFF,
}

enum class AccountStatus {
  PENDING,
  OPEN,
  PENDING_CLOSURE,
  CLOSED,
  CANCELLED,
}

object AccountConstants {
  const val INTERNAL_BANK_CUSTOMER_ID = "INTERNAL_BANK_CUSTOMER"
}

@Repository
interface AccountRepository : AbstractJpaRepository<Account> {
  fun findByAccountId(accountId: UUID): Account?

  fun findByAccountIdAndStatus(
    accountId: UUID,
    status: AccountStatus,
  ): Account?

  fun findByInternalIsTrueAndCustomerIdAndInternalAccountRole(
    customerId: String,
    internalAccountRole: InternalAccountRole,
  ): Account?
}
