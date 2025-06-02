package iterator.nucleus.parameter

import iterator.nucleus.AbstractMutableJpaRepositoryTest
import iterator.nucleus.TestingFu.randomAlphabetic
import iterator.nucleus.TestingFu.randomWords
import jakarta.persistence.EntityManager
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
  }
