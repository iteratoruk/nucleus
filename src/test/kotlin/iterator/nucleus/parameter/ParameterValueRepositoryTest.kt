package iterator.nucleus.parameter

import iterator.nucleus.AbstractMutableJpaRepositoryTest
import iterator.nucleus.TestingFu.randomAlphabetic
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.TestingFu.randomEnum
import iterator.nucleus.TestingFu.randomInstant
import iterator.nucleus.truncatedToPostgresAccuracy
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

val NOW: Instant = Instant.now().truncatedToPostgresAccuracy()
val AN_ACCOUNT_ID: UUID = UUID.randomUUID()
val ANOTHER_ACCOUNT_ID: UUID = UUID.randomUUID()
val AN_ACCOUNT_TEMPLATE_ID: String = randomAlphanumeric(16)
val ANOTHER_ACCOUNT_TEMPLATE_ID: String = randomAlphanumeric(16)
val A_CUSTOMER_TRANCHE_ID: UUID = UUID.randomUUID()
val ANOTHER_CUSTOMER_TRANCHE_ID: UUID = UUID.randomUUID()

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
  val accountId: UUID = AN_ACCOUNT_ID,
  val accountTemplateId: String = AN_ACCOUNT_TEMPLATE_ID,
  val customerTrancheId: UUID? = null,
)

data class ExpectedEffectiveParameter(
  override val name: String,
  override val value: String,
  override val level: ParameterLevel,
  override val resourceId: String? = null,
  override val effectiveFrom: Instant = NOW,
  override val effectiveTo: Instant? = null,
) : EffectiveParameter {
  companion object {
    fun fromInterface(param: EffectiveParameter): ExpectedEffectiveParameter =
      ExpectedEffectiveParameter(
        name = param.name,
        value = param.value,
        level = param.level,
        resourceId = param.resourceId,
        effectiveFrom = param.effectiveFrom,
        effectiveTo = param.effectiveTo,
      )
  }
}

class ParameterValueRepositoryTest
  @Autowired
  constructor(
    repo: ParameterValueRepository,
    em: EntityManager,
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractMutableJpaRepositoryTest<ParameterValue, ParameterValueRepository>(repo, em, ctx, mvc) {
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
                          value = "0.01",
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
                    name = "INTEREST_RATE",
                    level = ParameterLevel.GLOBAL,
                    value = "0.01",
                    effectiveFrom = NOW,
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
                          value = "0.01",
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = "0.02",
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
                    name = "INTEREST_RATE",
                    value = "0.02",
                    level = ParameterLevel.ACCOUNT_TEMPLATE,
                    resourceId = AN_ACCOUNT_TEMPLATE_ID,
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
                          value = "0.01",
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = "0.02",
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
                    name = "INTEREST_RATE",
                    value = "0.01",
                    level = ParameterLevel.GLOBAL,
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
                          value = "0.01",
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = "0.02",
                          resourceId = AN_ACCOUNT_TEMPLATE_ID,
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.CUSTOMER_TRANCHE,
                          value = "0.03",
                          resourceId = A_CUSTOMER_TRANCHE_ID.toString(),
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
                    name = "INTEREST_RATE",
                    value = "0.03",
                    level = ParameterLevel.CUSTOMER_TRANCHE,
                    resourceId = A_CUSTOMER_TRANCHE_ID.toString(),
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
                          value = "0.01",
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = "0.02",
                          resourceId = AN_ACCOUNT_TEMPLATE_ID,
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.CUSTOMER_TRANCHE,
                          value = "0.03",
                          resourceId = ANOTHER_CUSTOMER_TRANCHE_ID.toString(),
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
                    name = "INTEREST_RATE",
                    value = "0.02",
                    level = ParameterLevel.ACCOUNT_TEMPLATE,
                    resourceId = AN_ACCOUNT_TEMPLATE_ID,
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
                          value = "0.01",
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = "0.02",
                          resourceId = AN_ACCOUNT_TEMPLATE_ID,
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.CUSTOMER_TRANCHE,
                          value = "0.03",
                          resourceId = A_CUSTOMER_TRANCHE_ID.toString(),
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT,
                          value = "0.04",
                          resourceId = AN_ACCOUNT_ID.toString(),
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
                    name = "INTEREST_RATE",
                    value = "0.04",
                    level = ParameterLevel.ACCOUNT,
                    resourceId = AN_ACCOUNT_ID.toString(),
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
                          value = "0.01",
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT_TEMPLATE,
                          value = "0.02",
                          resourceId = AN_ACCOUNT_TEMPLATE_ID,
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.CUSTOMER_TRANCHE,
                          value = "0.03",
                          resourceId = A_CUSTOMER_TRANCHE_ID.toString(),
                        ),
                        ParameterSetupValue(
                          level = ParameterLevel.ACCOUNT,
                          value = "0.04",
                          resourceId = ANOTHER_ACCOUNT_ID.toString(),
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
                    name = "INTEREST_RATE",
                    value = "0.03",
                    level = ParameterLevel.CUSTOMER_TRANCHE,
                    resourceId = A_CUSTOMER_TRANCHE_ID.toString(),
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
                          value = "0.01",
                          resourceId = AN_ACCOUNT_TEMPLATE_ID,
                          effectiveFrom = NOW.minus(1, ChronoUnit.DAYS),
                          effectiveTo = NOW,
                        ),
                        // The GLOBAL level value, which should win. Its start time
                        // is INCLUSIVE.
                        ParameterSetupValue(
                          level = ParameterLevel.GLOBAL,
                          value = "0.02",
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
                    name = "INTEREST_RATE",
                    value = "0.02",
                    level = ParameterLevel.GLOBAL,
                    effectiveFrom = NOW,
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
        level = randomEnum(),
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
      entity.level = randomEnum()
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
