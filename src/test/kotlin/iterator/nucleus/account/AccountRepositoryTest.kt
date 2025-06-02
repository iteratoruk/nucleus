package iterator.nucleus.account

import iterator.nucleus.AbstractMutableJpaRepositoryTest
import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountFeature
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.aValidCustomerTranche
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
    val featureRepository: AccountFeatureRepository,
  ) : AbstractMutableJpaRepositoryTest<Account, AccountRepository>(repo, em, ctx, mvc) {
    override fun randomValidEntity(): Account {
      val accountTemplate = aValidAccountTemplate()
      val customerTranche = aValidCustomerTranche()
      persistAndFlush(listOf(accountTemplate, customerTranche))
      return aValidAccount(accountTemplate, customerTranche)
    }

    override fun entityClass(): Class<Account> = Account::class.java

    override fun mutateEntity(entity: Account) {
      entity.accountId = UUID.randomUUID()
    }

    @Test
    fun `should delete feature associations when deleting an account`() {
      // given
      val account = randomValidEntity()
      val feature = aValidAccountFeature()
      persistAndFlush(listOf(account, feature))
      account.features.add(feature)
      persistAndFlush(account)
      clear()
      val f1 = featureRepository.findById(feature.id).get()
      assertThat(f1.accounts).contains(account)
      clear()
      val updated = find(account.id)!!

      // when
      repo.delete(updated)
      flush()

      // then
      val f2 = featureRepository.findById(feature.id).get()
      assertThat(f2.accounts).isEmpty()
    }
  }
