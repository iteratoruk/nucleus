package iterator.nucleus.parameter

import iterator.nucleus.Serialization
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.TestingFu.randomBigDecimal
import iterator.nucleus.TestingFu.randomBoolean
import iterator.nucleus.TestingFu.randomDouble
import iterator.nucleus.TestingFu.randomEnum
import iterator.nucleus.TestingFu.randomFloat
import iterator.nucleus.TestingFu.randomInstant
import iterator.nucleus.TestingFu.randomInt
import iterator.nucleus.TestingFu.randomLocalDate
import iterator.nucleus.TestingFu.randomLocalDateTimeInThePast
import iterator.nucleus.TestingFu.randomLong
import iterator.nucleus.TestingFu.randomUUID
import iterator.nucleus.truncatedToPostgresAccuracy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.given
import org.mockito.kotlin.refEq
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test

@ExtendWith(MockitoExtension::class)
class ParameterValueServiceTest(
  @Mock val repo: ParameterValueRepository,
  @Mock val definitionRepository: ParameterDefinitionRepository,
) {
  val service = ParameterValueService(repo, definitionRepository, Serialization.mapper)

  @Test
  fun `should bind parameter values`() {
    // given
    val str = randomAlphanumeric(16)
    val now = Instant.now()
    val accountId = UUID.randomUUID()
    val accountTemplateId = randomAlphanumeric(32)
    val customerTrancheId = UUID.randomUUID()
    val expected =
      TestParams(
        stringProp = str,
        intProp = randomInt(),
        boolProp = randomBoolean(),
        doubleProp = randomDouble(),
        floatProp = randomFloat(),
        bigDecimalProp = randomBigDecimal(),
        longProp = randomLong(),
        dateProp = randomLocalDate(),
        datetimeProp = randomLocalDateTimeInThePast(),
        instantProp = randomInstant(),
      )
    val effectiveParams =
      listOf(
        param("stringProp", expected.stringProp),
        param("intProp", expected.intProp.toString()),
        param("boolProp", expected.boolProp.toString()),
        param("doubleProp", expected.doubleProp.toString()),
        param("floatProp", expected.floatProp.toString()),
        param("bigDecimalProp", expected.bigDecimalProp.toString()),
        param("longProp", expected.longProp.toString()),
        param("dateProp", expected.dateProp.toString()),
        param("datetimeProp", expected.datetimeProp.toString()),
        param("instantProp", expected.instantProp.toString()),
      )
    given {
      repo.findEffectiveParameters(
        parameterNames =
          eq(
            setOf(
              "stringProp",
              "intProp",
              "boolProp",
              "doubleProp",
              "floatProp",
              "bigDecimalProp",
              "longProp",
              "dateProp",
              "datetimeProp",
              "instantProp",
            ),
          ),
        effectiveAt = eq(now),
        accountId = eq(accountId),
        accountTemplateId = eq(accountTemplateId),
        customerTrancheId = eq(customerTrancheId),
      )
    }.willReturn(effectiveParams)

    // when
    val actual =
      service.findAndBindEffectiveParameters(
        TestParams::class,
        now,
        accountId,
        accountTemplateId,
        customerTrancheId,
      )

    // then
    assertThat(actual).isEqualTo(expected)
  }

  @Test
  fun `should throw given request to bind to class that is not data class when find and bind`() {
    assertThrows<IllegalArgumentException> {
      service.findAndBindEffectiveParameters(
        NotDataClass::class,
        Instant.now(),
        UUID.randomUUID(),
        randomUUID(),
        UUID.randomUUID(),
      )
    }
  }

  @Test
  fun `should throw given request to create value for non-existent parameter definition`() {
    // given
    given { definitionRepository.findByName(any()) }.willReturn(null)

    // when ... then
    assertThrows<IllegalArgumentException> {
      service.createParameterValue(
        parameterDefinitionName = randomAlphanumeric(8),
        value = randomAlphanumeric(16),
        level = randomEnum(),
        resourceId = randomUUID(),
      )
    }
  }

  @Test
  fun `should return created value when create parameter value with valid args`() {
    // given
    val definition = ParameterDefinition(name = randomAlphanumeric(8))
    val value = randomAlphanumeric(16)
    val level = randomEnum<ParameterLevel>()
    val resourceId = randomUUID()
    val effectiveFrom = randomInstant().truncatedToPostgresAccuracy()
    val effectiveTo = randomInstant().truncatedToPostgresAccuracy()
    given { definitionRepository.findByName(definition.name) }.willReturn(definition)
    val expected =
      ParameterValue(
        definition = definition,
        level = level,
        resourceId = resourceId,
        value = value,
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
      )
    given { repo.save(refEq(expected)) }.willReturn(expected)

    // when
    val actual =
      service.createParameterValue(
        parameterDefinitionName = definition.name,
        value = value,
        level = level,
        resourceId = resourceId,
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
      )

    // then
    assertThat(actual).isEqualTo(expected)
  }

  private fun param(
    name: String,
    value: String,
  ): EffectiveParameter =
    ExpectedEffectiveParameter(
      name = name,
      value = value,
      level = randomEnum(),
      resourceId = randomUUID(),
      effectiveFrom = randomInstant(),
      effectiveTo = randomInstant(),
    )
}

data class TestParams(
  val stringProp: String,
  val intProp: Int,
  val boolProp: Boolean,
  val doubleProp: Double,
  val floatProp: Float,
  val bigDecimalProp: BigDecimal,
  val longProp: Long,
  val dateProp: LocalDate,
  val datetimeProp: LocalDateTime,
  val instantProp: Instant,
)

class NotDataClass(
  val stringProp: String,
)
