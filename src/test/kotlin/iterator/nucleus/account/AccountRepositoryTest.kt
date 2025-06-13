package iterator.nucleus.account

import iterator.nucleus.AbstractMutableJpaRepositoryTest
import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountFeature
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.aValidCustomerTranche
import iterator.nucleus.TestingFu.randomEnum
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
      return aValidAccount(accountTemplate = accountTemplate, customerTranche = customerTranche)
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

    @Test
    fun `should find account by account ID`() {
      // given
      val account = randomValidEntity()
      persistAndFlush(account)

      // when
      val actual = repo.findByAccountId(account.accountId)

      // then
      assertThat(actual).usingRecursiveComparison().isEqualTo(account)
    }

    @Test
    fun `should return null given non-existent account ID when find by account ID`() {
      // when
      val actual = repo.findByAccountId(UUID.randomUUID())

      // then
      assertThat(actual).isNull()
    }

    @Test
    fun `should find by account by account ID and status`() {
      // given
      val account = randomValidEntity()
      persistAndFlush(account)

      // when
      val actual = repo.findByAccountIdAndStatus(account.accountId, account.status)

      // then
      assertThat(actual).usingRecursiveComparison().isEqualTo(account)
    }

    @Test
    fun `should return null given non-existent account ID when find by account ID and status`() {
      // when
      val actual =
        repo.findByAccountIdAndStatus(UUID.randomUUID(), randomEnum(AccountStatus::class.java))

      // then
      assertThat(actual).isNull()
    }

    @Test
    fun `should return null given wrong status when find by account ID and status`() {
      // given
      val account = randomValidEntity().apply { status = AccountStatus.CLOSED }
      persistAndFlush(account)

      // when
      val actual = repo.findByAccountIdAndStatus(account.accountId, AccountStatus.OPEN)

      // then
      assertThat(actual).isNull()
    }

    @Test
    fun `should find internal account by customerID and role`() {
      // given
      val account =
        randomValidEntity().apply {
          internal = true
          internalAccountRole = InternalAccountRole.PROFIT_AND_LOSS
        }
      persistAndFlush(account)

      // when
      val actual =
        repo.findByInternalIsTrueAndCustomerIdAndInternalAccountRole(
          account.customerId,
          account.internalAccountRole!!,
        )

      // then
      assertThat(actual).usingRecursiveComparison().isEqualTo(account)
    }
  }
