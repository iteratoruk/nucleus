package iterator.nucleus.parameter

import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.AbstractJpaRepository
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.ManyToOne
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Entity
@Cache(region = "parameter-definitions", usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
class ParameterDefinition(
  var name: String,
  var displayName: String? = null,
  var description: String? = null,
) : AbstractJpaEntity()

@Repository interface ParameterDefinitionRepository : AbstractJpaRepository<ParameterDefinition>

@Entity
@Cache(region = "parameter-values", usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
class ParameterValue(
  @ManyToOne var definition: ParameterDefinition,
  @Enumerated(EnumType.STRING) var level: ParameterLevel,
  var resourceId: String? = null,
  @JdbcTypeCode(SqlTypes.JSON) var value: String,
  var effectiveFrom: Instant = Instant.now(),
  var effectiveTo: Instant? = null,
) : AbstractJpaEntity()

enum class ParameterLevel {
  GLOBAL,
  ACCOUNT_TEMPLATE,
  CUSTOMER_TRANCHE,
  ACCOUNT,
}

@Repository
interface ParameterValueRepository : AbstractJpaRepository<ParameterValue> {
  @Query(
    value =
      """
        WITH ranked_values AS (
            SELECT
                pv.id as pv_id,
                pd.name as name,
                pv.value as value,
                pv.level as level,
                pv.resource_id as resource_id,
                pv.effective_from as effective_from,
                pv.effective_to as effective_to,
                CASE pv.level
                    WHEN 'ACCOUNT' THEN 4
                    WHEN 'CUSTOMER_TRANCHE' THEN 3
                    WHEN 'ACCOUNT_TEMPLATE' THEN 2
                    WHEN 'GLOBAL' THEN 1
                    ELSE 0
                END as level_priority,
                ROW_NUMBER() OVER (
                    PARTITION BY pd.name
                    ORDER BY
                        CASE pv.level
                            WHEN 'ACCOUNT' THEN 4
                            WHEN 'CUSTOMER_TRANCHE' THEN 3
                            WHEN 'ACCOUNT_TEMPLATE' THEN 2
                            WHEN 'GLOBAL' THEN 1
                            ELSE 0
                        END DESC,
                        pv.effective_from DESC
                ) as rn
            FROM parameter_value pv
            JOIN parameter_definition pd ON pv.definition_id = pd.id
            WHERE pd.name IN (:parameterNames)
              AND pv.effective_from <= :effectiveAt
              AND (pv.effective_to IS NULL OR pv.effective_to > :effectiveAt)
              AND (
                (pv.level = 'ACCOUNT' AND pv.resource_id = :accountId) OR
                (pv.level = 'CUSTOMER_TRANCHE' AND pv.resource_id = :customerTrancheId) OR
                (pv.level = 'ACCOUNT_TEMPLATE' AND pv.resource_id = :accountTemplateId) OR
                (pv.level = 'GLOBAL')
              )
        )
        SELECT
            name,
            value,
            level,
            resource_id,
            effective_from,
            effective_to
        FROM ranked_values
        WHERE rn = 1
    """,
    nativeQuery = true,
  )
  fun findEffectiveParameters(
    @Param("parameterNames") parameterNames: Set<String>,
    @Param("effectiveAt") effectiveAt: Instant,
    @Param("accountId") accountId: String,
    @Param("accountTemplateId") accountTemplateId: String,
    @Param("customerTrancheId") customerTrancheId: String? = null,
  ): List<EffectiveParameter>
}

interface EffectiveParameter {
  fun getName(): String

  fun getValue(): String

  fun getLevel(): ParameterLevel

  fun getResourceId(): String?

  fun getEffectiveFrom(): Instant

  fun getEffectiveTo(): Instant?
}
