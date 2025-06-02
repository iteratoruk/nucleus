package iterator.nucleus.account

import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.AbstractMutableJpaEntity
import iterator.nucleus.account.template.AccountTemplate
import iterator.nucleus.customer.CustomerTranche
import jakarta.persistence.Entity
import jakarta.persistence.ManyToOne
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
import org.springframework.stereotype.Repository
import java.util.UUID

@Entity
@Cache(region = "accounts", usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
class Account(
  var accountId: UUID,
  @ManyToOne var accountTemplate: AccountTemplate,
  @ManyToOne var customerTranche: CustomerTranche? = null,
) : AbstractMutableJpaEntity()

@Repository interface AccountRepository : AbstractJpaRepository<Account>
