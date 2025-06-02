package iterator.nucleus.account

import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.AbstractMutableJpaEntity
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ManyToMany
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.stereotype.Repository

@Entity
@Cache(region = "account-features", usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
class AccountFeature(
  var name: String,
  @JdbcTypeCode(SqlTypes.JSON) var config: String? = null,
  @ManyToMany(mappedBy = "features", fetch = FetchType.EAGER)
  var accounts: MutableSet<Account> = mutableSetOf(),
) : AbstractMutableJpaEntity()

@Repository
interface AccountFeatureRepository : AbstractJpaRepository<AccountFeature> {
  fun findByName(name: String): AccountFeature?
}
