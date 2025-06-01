package iterator.nucleus.account.template

import iterator.nucleus.AbstractJpaRepositoryTest
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.randomWords
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.web.servlet.MockMvc

class AccountTemplateRepositoryTest
  @Autowired
  constructor(
    repo: AccountTemplateRepository,
    em: EntityManager,
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractJpaRepositoryTest<AccountTemplate, AccountTemplateRepository>(repo, em, ctx, mvc) {
    override fun randomValidEntity(): AccountTemplate = aValidAccountTemplate()

    override fun entityClass(): Class<AccountTemplate> = AccountTemplate::class.java

    override fun mutateEntity(entity: AccountTemplate) {
      entity.displayName = randomWords(3)
    }
  }
