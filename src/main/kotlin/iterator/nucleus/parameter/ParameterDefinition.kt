package iterator.nucleus.parameter

import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.AbstractMutableJpaEntity
import jakarta.persistence.Entity
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
import org.springframework.stereotype.Repository

@Entity
@Cache(region = "parameter-definitions", usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
class ParameterDefinition(
  var name: String,
  var displayName: String? = null,
  var description: String? = null,
) : AbstractMutableJpaEntity()

@Repository interface ParameterDefinitionRepository : AbstractJpaRepository<ParameterDefinition>
