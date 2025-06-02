package iterator.nucleus.parameter

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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Cache(region = "parameter-definitions", usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
class ParameterDefinition(
  var name: String,
  var displayName: String? = null,
  var description: String? = null,
) : AbstractMutableJpaEntity()

@Repository interface ParameterDefinitionRepository : AbstractJpaRepository<ParameterDefinition>

@Entity
@Cache(region = "parameter-values", usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
class ParameterValue(
  @ManyToOne var definition: ParameterDefinition,
  @Enumerated(EnumType.STRING) var level: ParameterLevel,
  var resourceId: String? = null,
  var value: String,
  @Enumerated(EnumType.STRING) var type: ParameterType = ParameterType.STRING,
  var effectiveFrom: Instant = Instant.now(),
  var effectiveTo: Instant? = null,
) : AbstractMutableJpaEntity()

enum class ParameterLevel {
  GLOBAL,
  ACCOUNT_TEMPLATE,
  CUSTOMER_TRANCHE,
  ACCOUNT,
}

@Suppress("UNCHECKED_CAST")
enum class ParameterType(
  val returnType: Class<*>,
) {
  STRING(String::class.java) {
    override fun <T> convert(value: String): T = value as T
  },
  INT(Int::class.java) {
    override fun <T> convert(value: String): T = value.toInt() as T
  },
  LONG(Long::class.java) {
    override fun <T> convert(value: String): T = value.toLong() as T
  },
  DOUBLE(Double::class.java) {
    override fun <T> convert(value: String): T = value.toDouble() as T
  },
  BIG_DECIMAL(BigDecimal::class.java) {
    override fun <T> convert(value: String): T = value.toBigDecimal() as T
  },
  BOOLEAN(Boolean::class.java) {
    override fun <T> convert(value: String): T = value.toBoolean() as T
  },
  DATE(LocalDate::class.java) {
    override fun <T> convert(value: String): T = LocalDate.parse(value) as T
  },
  DATETIME(LocalDateTime::class.java) {
    override fun <T> convert(value: String): T = LocalDateTime.parse(value) as T
  },
  ;

  abstract fun <T> convert(value: String): T
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
                pv.type as type,
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
            type,
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
  val name: String
  val value: String
  val type: ParameterType
  val level: ParameterLevel
  val resourceId: String?
  val effectiveFrom: Instant
  val effectiveTo: Instant?
}
