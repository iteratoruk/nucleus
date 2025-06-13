package iterator.nucleus.parameter

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.AbstractMutableJpaEntity
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.ManyToOne
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

@Entity
@Cache(region = "parameter-values", usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
class ParameterValue(
  @ManyToOne var definition: ParameterDefinition,
  @Enumerated(EnumType.STRING) var level: ParameterLevel,
  var resourceId: String? = null,
  var value: String,
  var effectiveFrom: Instant = Instant.now(),
  var effectiveTo: Instant? = null,
) : AbstractMutableJpaEntity()

@Service
class ParameterValueService(
  val repo: ParameterValueRepository,
  val om: ObjectMapper,
) {
  private val parameterNamesCache: ConcurrentMap<KClass<*>, Set<String>> = ConcurrentHashMap()

  private val typesCache: ConcurrentMap<KClass<*>, JavaType> = ConcurrentHashMap()

  fun <T : Any> findAndBindEffectiveParameters(
    dataClass: KClass<T>,
    effectiveAt: Instant,
    accountId: UUID,
    accountTemplateId: String,
    customerTrancheId: UUID? = null,
  ): T {
    require(dataClass.isData) { "${dataClass.simpleName} must be a data class" }
    val parameterNames =
      parameterNamesCache.computeIfAbsent(dataClass) { cls ->
        cls.primaryConstructor
          ?.parameters
          ?.mapNotNull { it.name }
          ?.toSet() ?: emptySet()
      }
    val effectiveParameters =
      repo
        .findEffectiveParameters(
          parameterNames = parameterNames,
          effectiveAt = effectiveAt,
          accountId = accountId,
          accountTemplateId = accountTemplateId,
          customerTrancheId = customerTrancheId,
        ).associate { it.name to it.value }
    val javaType =
      typesCache.computeIfAbsent(dataClass) { cls -> om.typeFactory.constructType(cls.java) }
    return om.convertValue(effectiveParameters, javaType)
  }
}

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
                (pv.level = 'ACCOUNT' AND pv.resource_id = cast(:accountId as varchar)) OR
                (pv.level = 'CUSTOMER_TRANCHE' AND pv.resource_id = cast(:customerTrancheId as varchar)) OR
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
    @Param("accountId") accountId: UUID,
    @Param("accountTemplateId") accountTemplateId: String,
    @Param("customerTrancheId") customerTrancheId: UUID? = null,
  ): List<EffectiveParameter>
}

interface EffectiveParameter {
  val name: String
  val value: String
  val level: ParameterLevel
  val resourceId: String?
  val effectiveFrom: Instant
  val effectiveTo: Instant?
}
