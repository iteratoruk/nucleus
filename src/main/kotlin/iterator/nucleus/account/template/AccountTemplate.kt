package iterator.nucleus.account.template

import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.AbstractMutableJpaEntity
import iterator.nucleus.account.feature.balancelimit.BalanceLimitFeatureParameters
import iterator.nucleus.account.feature.interest.InterestFeatureParameters
import jakarta.persistence.Entity
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository

@Entity
@Cache(region = "account-templates", usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
class AccountTemplate(
  var accountTemplateId: String,
  var displayName: String,
  @JdbcTypeCode(SqlTypes.JSON) var currentRepresentation: AccountTemplateRepresentation,
) : AbstractMutableJpaEntity()

data class AccountTemplateRepresentation(
  val accountTemplateId: String,
  val displayName: String,
  val interestFeatureEnabled: Boolean = false,
  val interestFeatureParams: InterestFeatureParameters? = null,
  val balanceLimitFeatureEnabled: Boolean = false,
  val balanceLimitFeatureParams: BalanceLimitFeatureParameters? = null,
)

@Repository
interface AccountTemplateRepository : AbstractJpaRepository<AccountTemplate> {
  fun findByCreatedBy(
    createdBy: String,
    page: Pageable,
  ): Page<AccountTemplate>
}
