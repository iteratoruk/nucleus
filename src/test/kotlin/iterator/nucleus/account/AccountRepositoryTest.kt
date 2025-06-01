package iterator.nucleus.account

import iterator.nucleus.AbstractJpaRepositoryTest
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.aValidCustomerTranche
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.web.servlet.MockMvc
import java.util.UUID

class AccountRepositoryTest
  @Autowired
  constructor(
    repo: AccountRepository,
    em: EntityManager,
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractJpaRepositoryTest<Account, AccountRepository>(repo, em, ctx, mvc) {
    override fun randomValidEntity(): Account {
      val accountTemplate = aValidAccountTemplate()
      val customerTranche = aValidCustomerTranche()
      persistAndFlush(listOf(accountTemplate, customerTranche))
      return Account(
        accountId = UUID.randomUUID(),
        accountTemplate = accountTemplate,
        customerTranche = customerTranche,
      )
    }

    override fun entityClass(): Class<Account> = Account::class.java

    override fun mutateEntity(entity: Account) {
      entity.accountId = UUID.randomUUID()
    }
  }
