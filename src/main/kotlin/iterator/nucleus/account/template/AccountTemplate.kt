package iterator.nucleus.account.template

import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.AbstractJpaRepository
import jakarta.persistence.Entity
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
import org.springframework.stereotype.Repository

@Entity
@Cache(region = "account-templates", usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
class AccountTemplate(
  var accountTemplateId: String,
  var displayName: String,
) : AbstractJpaEntity()

@Repository interface AccountTemplateRepository : AbstractJpaRepository<AccountTemplate>
