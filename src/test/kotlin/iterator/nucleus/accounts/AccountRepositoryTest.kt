package iterator.nucleus.accounts

import iterator.nucleus.AbstractJpaRepositoryTest
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.TestingFu.randomEnum
import iterator.nucleus.parameters.LedgerSide
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
    override fun entityClass() = Account::class.java

    override fun randomValidEntity() =
      Account(
        accountIdentifier = UUID.randomUUID(),
        stakeholderIdentifier = randomAlphanumeric(16),
        classificationCode = randomAlphanumeric(16),
        ledgerSide = randomEnum<LedgerSide>(),
        status = AccountStatus.OPEN,
      )

    @Test
    fun `findByAccountIdentifier returns the account when one exists with the given identifier`() {
      val account = randomValidEntity()
      persistAndFlush(account)

      assertThat(repo.findByAccountIdentifier(account.accountIdentifier)).isEqualTo(account)
    }

    @Test
    fun `findByAccountIdentifier returns null when no account exists with the given identifier`() {
      assertThat(repo.findByAccountIdentifier(UUID.randomUUID())).isNull()
    }
  }
