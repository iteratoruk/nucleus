package iterator.nucleus.parameter

import iterator.nucleus.AbstractJpaRepositoryTest
import iterator.nucleus.TestingFu.randomAlphabetic
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.TestingFu.randomEnum
import iterator.nucleus.TestingFu.randomInstant
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.web.servlet.MockMvc
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.stream.Stream

val NOW: Instant = Instant.now()
val AN_ACCOUNT_ID: String = UUID.randomUUID().toString()
val ANOTHER_ACCOUNT_ID: String = UUID.randomUUID().toString()
val AN_ACCOUNT_TEMPLATE_ID: String = randomAlphanumeric(16)
val ANOTHER_ACCOUNT_TEMPLATE_ID: String = randomAlphanumeric(16)
val A_CUSTOMER_TRANCHE_ID: String = UUID.randomUUID().toString()
val ANOTHER_CUSTOMER_TRANCHE_ID: String = UUID.randomUUID().toString()

fun jsonString(value: String): String = """{"value": "$value"}"""

data class ParameterResolutionScenario(
  val description: String,
  val parameters: List<ParameterSetup> = emptyList(),
  val args: EffectiveParameterArgs,
  val expected: List<ExpectedEffectiveParameter> = emptyList(),
) {
  override fun toString(): String = description
}

data class ParameterSetup(
  val name: String,
  val values: List<ParameterSetupValue> = emptyList(),
)

data class ParameterSetupValue(
  val level: ParameterLevel,
  val value: String,
  val resourceId: String? = null,
  val effectiveFrom: Instant = NOW,
  val effectiveTo: Instant? = null,
)

data class EffectiveParameterArgs(
  val parameterNames: Set<String>,
  val effectiveAt: Instant = NOW,
  val accountId: String = AN_ACCOUNT_ID,
  val accountTemplateId: String = AN_ACCOUNT_TEMPLATE_ID,
  val customerTrancheId: String? = null,
)

data class ExpectedEffectiveParameter(
  val expectedName: String,
  val expectedValue: String,
  val expectedLevel: ParameterLevel,
  val expectedResourceId: String? = null,
  val expectedEffectiveFrom: Instant = NOW,
  val expectedEffectiveTo: Instant? = null,
) : EffectiveParameter {
  companion object {
    fun fromInterface(param: EffectiveParameter): ExpectedEffectiveParameter =
      ExpectedEffectiveParameter(
        expectedName = param.getName(),
        expectedValue = param.getValue(),
        expectedLevel = param.getLevel(),
        expectedResourceId = param.getResourceId(),
        expectedEffectiveFrom = param.getEffectiveFrom(),
        expectedEffectiveTo = param.getEffectiveTo(),
      )
  }

  override fun getName(): String = expectedName

  override fun getValue(): String = expectedValue

  override fun getLevel(): ParameterLevel = expectedLevel

  override fun getResourceId(): String? = expectedResourceId

  override fun getEffectiveFrom(): Instant = expectedEffectiveFrom

  override fun getEffectiveTo(): Instant? = expectedEffectiveTo
}

class ParameterValueRepositoryTest
  @Autowired
  constructor(
    repo: ParameterValueRepository,
    em: EntityManager,
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractJpaRepositoryTest<ParameterValue, ParameterValueRepository>(repo, em, ctx, mvc) {
    companion object {
      @JvmStatic
      fun parameterResolutionScenarios(): Stream<Arguments> =
        Stream.of(
          Arguments.of(
            ParameterResolutionScenario(
              description = "no parameters defined and no values should return no results",
              args = EffectiveParameterArgs(parameterNames = setOf("INTEREST_RATE")),
            ),
          ),
          Arguments.of(
            ParameterResolutionScenario(
              description = "one parameter defined with no values should return no results",
              parameters = listOf(ParameterSetup(name = "INTEREST_RATE")),
              args = EffectiveParameterArgs(parameterNames = setOf("INTEREST_RATE")),
            ),
          ),
          Arguments.of(
            ParameterResolutionScenario(
              description =
                "one parameter defined with a value at global level should resolve to global level",
              parameters =
                listOf(
                  ParameterSetup(
                    name = "INTEREST_RATE",
                    values =
                      listOf(
                        ParameterSetupValue(
                          level = ParameterLevel.GLOBAL,
                          value = jsonString("0.01"),
                        ),
                      ),
                  ),
                ),
              args =
                EffectiveParameterArgs(
                  parameterNames = setOf("INTEREST_RATE"),
                  effectiveAt = NOW,
                ),
              expected =
                listOf(
                  ExpectedEffectiveParameter(
                    expectedName = "INTEREST_RATE",
                    expectedLevel = ParameterLevel.GLOBAL,
                    expectedValue = jsonString("0.01"),
                    expectedEffectiveFrom = NOW,
                  ),
                ),
            ),
          ),
          Arguments.of(
            ParameterResolutionScenario(
              description =
                "a parameter overridden at account template level should return account template value",
              parameters =
                listOf(
                  ParameterSetup(
                    name = "INTEREST_RATE",
                    values =
                      listOf(
                        ParameterSetupValue(
                          level = ParameterLevel.GLOBAL,
                          value = jsonString("0.01"),
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = jsonString("0.02"),
                          resourceId = AN_ACCOUNT_TEMPLATE_ID,
                        ),
                      ),
                  ),
                ),
              args =
                EffectiveParameterArgs(
                  parameterNames = setOf("INTEREST_RATE"),
                  accountTemplateId = AN_ACCOUNT_TEMPLATE_ID,
                ),
              expected =
                listOf(
                  ExpectedEffectiveParameter(
                    expectedName = "INTEREST_RATE",
                    expectedValue = jsonString("0.02"),
                    expectedLevel = ParameterLevel.ACCOUNT_TEMPLATE,
                    expectedResourceId = AN_ACCOUNT_TEMPLATE_ID,
                  ),
                ),
            ),
          ),
          Arguments.of(
            ParameterResolutionScenario(
              description =
                "a parameter overridden at another account template level should return global value",
              parameters =
                listOf(
                  ParameterSetup(
                    name = "INTEREST_RATE",
                    values =
                      listOf(
                        ParameterSetupValue(
                          level = ParameterLevel.GLOBAL,
                          value = jsonString("0.01"),
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = jsonString("0.02"),
                          resourceId = ANOTHER_ACCOUNT_TEMPLATE_ID,
                        ),
                      ),
                  ),
                ),
              args =
                EffectiveParameterArgs(
                  parameterNames = setOf("INTEREST_RATE"),
                  accountTemplateId = AN_ACCOUNT_TEMPLATE_ID,
                ),
              expected =
                listOf(
                  ExpectedEffectiveParameter(
                    expectedName = "INTEREST_RATE",
                    expectedValue = jsonString("0.01"),
                    expectedLevel = ParameterLevel.GLOBAL,
                  ),
                ),
            ),
          ),
          Arguments.of(
            ParameterResolutionScenario(
              description =
                "a parameter overridden at customer tranche level should return customer tranche value",
              parameters =
                listOf(
                  ParameterSetup(
                    name = "INTEREST_RATE",
                    values =
                      listOf(
                        ParameterSetupValue(
                          level = ParameterLevel.GLOBAL,
                          value = jsonString("0.01"),
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = jsonString("0.02"),
                          resourceId = AN_ACCOUNT_TEMPLATE_ID,
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.CUSTOMER_TRANCHE,
                          value = jsonString("0.03"),
                          resourceId = A_CUSTOMER_TRANCHE_ID,
                        ),
                      ),
                  ),
                ),
              args =
                EffectiveParameterArgs(
                  parameterNames = setOf("INTEREST_RATE"),
                  accountTemplateId = AN_ACCOUNT_TEMPLATE_ID,
                  customerTrancheId = A_CUSTOMER_TRANCHE_ID,
                ),
              expected =
                listOf(
                  ExpectedEffectiveParameter(
                    expectedName = "INTEREST_RATE",
                    expectedValue = jsonString("0.03"),
                    expectedLevel = ParameterLevel.CUSTOMER_TRANCHE,
                    expectedResourceId = A_CUSTOMER_TRANCHE_ID,
                  ),
                ),
            ),
          ),
          Arguments.of(
            ParameterResolutionScenario(
              description =
                "a parameter overridden at another customer tranche level should return account template value",
              parameters =
                listOf(
                  ParameterSetup(
                    name = "INTEREST_RATE",
                    values =
                      listOf(
                        ParameterSetupValue(
                          level = ParameterLevel.GLOBAL,
                          value = jsonString("0.01"),
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = jsonString("0.02"),
                          resourceId = AN_ACCOUNT_TEMPLATE_ID,
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.CUSTOMER_TRANCHE,
                          value = jsonString("0.03"),
                          resourceId = ANOTHER_CUSTOMER_TRANCHE_ID,
                        ),
                      ),
                  ),
                ),
              args =
                EffectiveParameterArgs(
                  parameterNames = setOf("INTEREST_RATE"),
                  accountTemplateId = AN_ACCOUNT_TEMPLATE_ID,
                  customerTrancheId = A_CUSTOMER_TRANCHE_ID,
                ),
              expected =
                listOf(
                  ExpectedEffectiveParameter(
                    expectedName = "INTEREST_RATE",
                    expectedValue = jsonString("0.02"),
                    expectedLevel = ParameterLevel.ACCOUNT_TEMPLATE,
                    expectedResourceId = AN_ACCOUNT_TEMPLATE_ID,
                  ),
                ),
            ),
          ),
          Arguments.of(
            ParameterResolutionScenario(
              description =
                "a parameter overridden at account level should return account value",
              parameters =
                listOf(
                  ParameterSetup(
                    name = "INTEREST_RATE",
                    values =
                      listOf(
                        ParameterSetupValue(
                          level = ParameterLevel.GLOBAL,
                          value = jsonString("0.01"),
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = jsonString("0.02"),
                          resourceId = AN_ACCOUNT_TEMPLATE_ID,
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.CUSTOMER_TRANCHE,
                          value = jsonString("0.03"),
                          resourceId = A_CUSTOMER_TRANCHE_ID,
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT,
                          value = jsonString("0.04"),
                          resourceId = AN_ACCOUNT_ID,
                        ),
                      ),
                  ),
                ),
              args =
                EffectiveParameterArgs(
                  parameterNames = setOf("INTEREST_RATE"),
                  accountTemplateId = AN_ACCOUNT_TEMPLATE_ID,
                  customerTrancheId = A_CUSTOMER_TRANCHE_ID,
                  accountId = AN_ACCOUNT_ID,
                ),
              expected =
                listOf(
                  ExpectedEffectiveParameter(
                    expectedName = "INTEREST_RATE",
                    expectedValue = jsonString("0.04"),
                    expectedLevel = ParameterLevel.ACCOUNT,
                    expectedResourceId = AN_ACCOUNT_ID,
                  ),
                ),
            ),
          ),
          Arguments.of(
            ParameterResolutionScenario(
              description =
                "a parameter overridden at another account level should return customer tranche value",
              parameters =
                listOf(
                  ParameterSetup(
                    name = "INTEREST_RATE",
                    values =
                      listOf(
                        ParameterSetupValue(
                          level = ParameterLevel.GLOBAL,
                          value = jsonString("0.01"),
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = jsonString("0.02"),
                          resourceId = AN_ACCOUNT_TEMPLATE_ID,
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.CUSTOMER_TRANCHE,
                          value = jsonString("0.03"),
                          resourceId = A_CUSTOMER_TRANCHE_ID,
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT,
                          value = jsonString("0.04"),
                          resourceId = ANOTHER_ACCOUNT_ID,
                        ),
                      ),
                  ),
                ),
              args =
                EffectiveParameterArgs(
                  parameterNames = setOf("INTEREST_RATE"),
                  accountTemplateId = AN_ACCOUNT_TEMPLATE_ID,
                  customerTrancheId = A_CUSTOMER_TRANCHE_ID,
                  accountId = AN_ACCOUNT_ID,
                ),
              expected =
                listOf(
                  ExpectedEffectiveParameter(
                    expectedName = "INTEREST_RATE",
                    expectedValue = jsonString("0.03"),
                    expectedLevel = ParameterLevel.CUSTOMER_TRANCHE,
                    expectedResourceId = A_CUSTOMER_TRANCHE_ID,
                  ),
                ),
            ),
          ),
          Arguments.of(
            ParameterResolutionScenario(
              description =
                "BOUNDARY: a TEMPLATE value expires at the exact instant a GLOBAL one begins",
              parameters =
                listOf(
                  ParameterSetup(
                    name = "INTEREST_RATE",
                    values =
                      listOf(
                        // The TEMPLATE level value, which should lose. Its end time
                        // is EXCLUSIVE.
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = jsonString("0.01"),
                          resourceId = AN_ACCOUNT_TEMPLATE_ID,
                          effectiveFrom = NOW.minus(1, ChronoUnit.DAYS),
                          effectiveTo = NOW,
                        ),
                        // The GLOBAL level value, which should win. Its start time
                        // is INCLUSIVE.
                        ParameterSetupValue(
                          level = ParameterLevel.GLOBAL,
                          value = jsonString("0.02"),
                          effectiveFrom = NOW,
                        ),
                      ),
                  ),
                ),
              args =
                EffectiveParameterArgs(
                  parameterNames = setOf("INTEREST_RATE"),
                  effectiveAt = NOW, // The query happens exactly on the boundary
                ),
              expected =
                listOf(
                  ExpectedEffectiveParameter(
                    expectedName = "INTEREST_RATE",
                    expectedValue = jsonString("0.02"),
                    expectedLevel = ParameterLevel.GLOBAL,
                    expectedEffectiveFrom = NOW,
                  ),
                ),
            ),
          ),
        )
    }

    override fun randomValidEntity(): ParameterValue {
      val def = ParameterDefinition(name = randomAlphanumeric(8))
      persistAndFlush(def)
      return ParameterValue(
        definition = def,
        level = randomEnum(ParameterLevel::class.java),
        resourceId = randomAlphanumeric(36),
        value =
          """
          { "${randomAlphabetic(8)}": "${randomAlphanumeric(32)}" }
          """.trimIndent(),
        effectiveFrom = randomInstant(),
        effectiveTo = randomInstant(),
      )
    }

    override fun entityClass(): Class<ParameterValue> = ParameterValue::class.java

    override fun mutateEntity(entity: ParameterValue) {
      entity.level = randomEnum(ParameterLevel::class.java)
      entity.resourceId = randomAlphanumeric(36)
      entity.value =
        """
        { "${randomAlphabetic(8)}": "${randomAlphanumeric(32)}" }
        """.trimIndent()
      entity.effectiveFrom = randomInstant()
      entity.effectiveTo = randomInstant()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parameterResolutionScenarios")
    fun `expected parameter value resolution should conform to scenario expectations`(scenario: ParameterResolutionScenario) {
      // given
      scenario.parameters.forEach { d ->
        val definition = ParameterDefinition(name = d.name)
        persistAndFlush(definition)
        d.values.forEach { v ->
          val value =
            ParameterValue(
              definition = definition,
              level = v.level,
              value = v.value,
              resourceId = v.resourceId,
              effectiveFrom = v.effectiveFrom,
              effectiveTo = v.effectiveTo,
            )
          persistAndFlush(value)
        }
      }

      // when
      val actual =
        repo.findEffectiveParameters(
          parameterNames = scenario.args.parameterNames,
          effectiveAt = scenario.args.effectiveAt,
          accountId = scenario.args.accountId,
          accountTemplateId = scenario.args.accountTemplateId,
          customerTrancheId = scenario.args.customerTrancheId,
        )

      // then
      assertThat(actual.map { ExpectedEffectiveParameter.fromInterface(it) })
        .isEqualTo(scenario.expected)
    }
  }
