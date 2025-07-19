package iterator.nucleus.account

import iterator.nucleus.AbstractMutableJpaRepositoryTest
import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountFeature
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.randomAlphabetic
import iterator.nucleus.TestingFu.randomWords
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.web.servlet.MockMvc

class AccountFeatureRepositoryTest
  @Autowired
  constructor(
    repo: AccountFeatureRepository,
    em: EntityManager,
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractMutableJpaRepositoryTest<AccountFeature, AccountFeatureRepository>(repo, em, ctx, mvc) {
    override fun mutateEntity(entity: AccountFeature) {
      entity.config = """{"${randomWords(1).lowercase()}": "${randomWords(1).lowercase()}"}"""
    }

    override fun randomValidEntity(): AccountFeature = aValidAccountFeature(name = randomAlphabetic(16).uppercase())

    override fun entityClass(): Class<AccountFeature> = AccountFeature::class.java

    @Test
    fun `should not be able to delete a feature with an association to an account`() {
      // given
      val accountTemplate = aValidAccountTemplate()
      val account = aValidAccount(accountTemplate)
      val feature = aValidAccountFeature(name = randomAlphabetic(16).uppercase())
      persistAndFlush(listOf(accountTemplate, account, feature))
      account.features.add(feature)
      persistAndFlush(account)
      clear()
      val updated = find(feature.id)!!

      // when
      assertThrows<ConstraintViolationException> {
        repo.delete(updated)
        flush()
      }
    }

    @Test
    fun `should return zero given feature has no association with account when count by name and accounts contains`() {
      // given
      val accountTemplate = aValidAccountTemplate()
      val account = aValidAccount(accountTemplate)
      val feature = aValidAccountFeature(name = randomAlphabetic(16).uppercase())
      persistAndFlush(listOf(accountTemplate, account, feature))
      clear()

      // when
      val count = repo.countByNameAndAccountsContains(feature.name, account)

      // then
      assertThat(count).isZero()
    }

    @Test
    fun `should return one given feature has association with account when count by name and accounts contains`() {
      // given
      val accountTemplate = aValidAccountTemplate()
      val account = aValidAccount(accountTemplate)
      val feature = aValidAccountFeature(name = randomAlphabetic(16).uppercase())
      account.features.add(feature)
      feature.accounts.add(account)
      persistAndFlush(listOf(accountTemplate, account, feature))
      clear()

      // when
      val count = repo.countByNameAndAccountsContains(feature.name, account)

      // then
      assertThat(count).isOne()
    }
  }
