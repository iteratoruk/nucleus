package iterator.nucleus.customer

import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.AbstractJpaRepository
import jakarta.persistence.Entity
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
import org.springframework.stereotype.Repository
import java.util.UUID

@Entity
@Cache(region = "customer-tranches", usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
class CustomerTranche(
  var customerTrancheId: UUID,
  var displayName: String,
) : AbstractJpaEntity()

@Repository interface CustomerTrancheRepository : AbstractJpaRepository<CustomerTranche>
