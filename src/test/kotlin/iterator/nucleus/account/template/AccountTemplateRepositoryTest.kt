package iterator.nucleus.account.template

import iterator.nucleus.AbstractMutableJpaRepositoryTest
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.TestingFu.randomWords
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.test.web.servlet.MockMvc

class AccountTemplateRepositoryTest
  @Autowired
  constructor(
    repo: AccountTemplateRepository,
    em: EntityManager,
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractMutableJpaRepositoryTest<AccountTemplate, AccountTemplateRepository>(
      repo,
      em,
      ctx,
      mvc,
    ) {
    override fun randomValidEntity(): AccountTemplate = aValidAccountTemplate()

    override fun entityClass(): Class<AccountTemplate> = AccountTemplate::class.java

    override fun mutateEntity(entity: AccountTemplate) {
      entity.displayName = randomWords(3)
    }

    @Test
    fun `should find account template representations by created by`() {
      // given
      val clientA = randomAlphanumeric(16)
      val clientB = randomAlphanumeric(16)
      val template1 = randomValidEntity().apply { createdBy = clientA }
      val template2 = randomValidEntity().apply { createdBy = clientB }
      val template3 = randomValidEntity().apply { createdBy = clientA }
      persistAndFlush(listOf(template1, template2, template3))
      val page = PageRequest.of(0, 20)

      // when
      val actual = repo.findByCreatedBy(clientA, page)

      // then
      val expected =
        PageImpl(
          listOf(template1, template3),
          page,
          2,
        )
      assertThat(actual).isEqualTo(expected)
    }
  }
