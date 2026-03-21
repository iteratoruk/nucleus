package iterator.nucleus.parameters

import iterator.nucleus.AbstractJpaRepositoryTest
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.TestingFu.randomEnum
import iterator.nucleus.TestingFu.randomInstant
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.web.servlet.MockMvc

class ParameterNodeRepositoryTest
  @Autowired
  constructor(
    repo: ParameterNodeRepository,
    em: EntityManager,
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractJpaRepositoryTest<ParameterNode, ParameterNodeRepository>(repo, em, ctx, mvc) {
    override fun entityClass() = ParameterNode::class.java

    override fun randomValidEntity() =
      ParameterNode(
        classificationCode = randomAlphanumeric(16),
        ledgerSide = randomEnum<LedgerSide>(),
      )

    @Test
    fun `findByClassificationCode returns the node when it exists`() {
      val node = randomValidEntity()
      persistAndFlush(node)

      assertThat(repo.findByClassificationCode(node.classificationCode)).isEqualTo(node)
    }

    @Test
    fun `findByClassificationCode returns null when no node exists for the classification code`() {
      assertThat(repo.findByClassificationCode(randomAlphanumeric(16))).isNull()
    }
  }

class ParameterValueRepositoryTest
  @Autowired
  constructor(
    repo: ParameterValueRepository,
    em: EntityManager,
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractJpaRepositoryTest<ParameterValue, ParameterValueRepository>(repo, em, ctx, mvc) {
    override fun entityClass() = ParameterValue::class.java

    override fun randomValidEntity(): ParameterValue {
      val node =
        ParameterNode(
          classificationCode = randomAlphanumeric(16),
          ledgerSide = randomEnum<LedgerSide>(),
        )
      persistAndFlush(node)
      return ParameterValue(
        parameterNode = node,
        parameterKey = randomAlphanumeric(16),
        value = randomAlphanumeric(16),
        effectiveDatetime = randomInstant(),
      )
    }
  }
