package iterator.nucleus.parameter

import iterator.nucleus.AbstractMutableJpaRepositoryTest
import iterator.nucleus.TestingFu.randomAlphabetic
import iterator.nucleus.TestingFu.randomWords
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.web.servlet.MockMvc

class ParameterDefinitionRepositoryTest
  @Autowired
  constructor(
    repo: ParameterDefinitionRepository,
    em: EntityManager,
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractMutableJpaRepositoryTest<ParameterDefinition, ParameterDefinitionRepository>(
      repo,
      em,
      ctx,
      mvc,
    ) {
    override fun randomValidEntity(): ParameterDefinition =
      ParameterDefinition(
        name = randomAlphabetic(8),
        displayName = randomWords(3),
        description = randomWords(8),
      )

    override fun entityClass(): Class<ParameterDefinition> = ParameterDefinition::class.java

    override fun mutateEntity(entity: ParameterDefinition) {
      entity.name = randomAlphabetic(8)
      entity.displayName = randomWords(3)
      entity.description = randomWords(8)
    }

    @Test
    fun `should return null given non-existent name when find by name`() {
      assertThat(repo.findByName(randomAlphabetic(8))).isNull()
    }

    @Test
    fun `shhould return definition given name when find by name`() {
      // given
      val definition = randomValidEntity()
      persistAndFlush(definition)

      // when
      val actual = repo.findByName(definition.name)

      // then
      assertThat(actual).usingRecursiveComparison().isEqualTo(definition)
    }
  }
