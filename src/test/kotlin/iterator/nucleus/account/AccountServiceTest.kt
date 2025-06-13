package iterator.nucleus.account

import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.aValidInternalAccount
import iterator.nucleus.TestingFu.randomEnum
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.given
import java.util.UUID
import kotlin.test.Test

@ExtendWith(MockitoExtension::class)
class AccountServiceTest(
  @Mock val repo: AccountRepository,
) {
  val service = AccountService(repo)

  @Test
  fun `should return existing open account when find required open account`() {
    // given
    val expected =
      aValidAccount(accountTemplate = aValidAccountTemplate(), status = AccountStatus.OPEN)
    given { repo.findByAccountIdAndStatus(eq(expected.accountId), eq(AccountStatus.OPEN)) }
      .willReturn(expected)

    // when
    val actual = service.findRequiredOpenAccount(expected.accountId)

    // then
    assertThat(actual).isEqualTo(expected)
  }

  @Test
  fun `should throw given no open account with given ID when find required open account`() {
    // given
    given { repo.findByAccountIdAndStatus(any(), eq(AccountStatus.OPEN)) }.willReturn(null)

    // when ... then
    assertThrows<IllegalArgumentException> { service.findRequiredOpenAccount(UUID.randomUUID()) }
  }

  @Test
  fun `should return existing internal account when find required internal account`() {
    // given
    val expected = aValidInternalAccount()
    given {
      repo.findByInternalIsTrueAndCustomerIdAndInternalAccountRole(
        customerId = eq(AccountConstants.ATOM_BANK_CUSTOMER_ID),
        internalAccountRole = eq(expected.internalAccountRole!!),
      )
    }.willReturn(expected)

    // when
    val actual = service.findRequiredInternalAccount(expected.internalAccountRole!!)

    // then
    assertThat(actual).isEqualTo(expected)
  }

  @Test
  fun `should throw given no internal account with given role when find required internal account`() {
    // given
    given {
      repo.findByInternalIsTrueAndCustomerIdAndInternalAccountRole(
        customerId = eq(AccountConstants.ATOM_BANK_CUSTOMER_ID),
        internalAccountRole = any(),
      )
    }.willReturn(null)

    // when ... then
    assertThrows<java.lang.IllegalArgumentException> {
      service.findRequiredInternalAccount(randomEnum(InternalAccountRole::class.java))
    }
  }

  @Test
  fun `should throw given first account not found when find required account pair`() {
    // given
    val template = aValidAccountTemplate()
    val first = aValidAccount(template).apply { id = 1 }
    val second = aValidAccount(template).apply { id = 2 }
    given { repo.findByAccountId(eq(first.accountId)) }.willReturn(null)
    given { repo.findByAccountId(eq(second.accountId)) }.willReturn(second)

    // when ... then
    assertThrows<java.lang.IllegalArgumentException> {
      service.findRequiredAccountPair(first.accountId, second.accountId)
    }
  }

  @Test
  fun `should throw given second account not found when find required account pair`() {
    // given
    val template = aValidAccountTemplate()
    val first = aValidAccount(template).apply { id = 1 }
    val second = aValidAccount(template).apply { id = 2 }
    given { repo.findByAccountId(eq(first.accountId)) }.willReturn(first)
    given { repo.findByAccountId(eq(second.accountId)) }.willReturn(null)

    // when ... then
    assertThrows<java.lang.IllegalArgumentException> {
      service.findRequiredAccountPair(first.accountId, second.accountId)
    }
  }

  @Test
  fun `should ind required account pair`() {
    // given
    val template = aValidAccountTemplate()
    val first = aValidAccount(template).apply { id = 1 }
    val second = aValidAccount(template).apply { id = 2 }
    given { repo.findByAccountId(eq(first.accountId)) }.willReturn(first)
    given { repo.findByAccountId(eq(second.accountId)) }.willReturn(second)

    // when ... then
    val actual = service.findRequiredAccountPair(first.accountId, second.accountId)

    // then
    assertThat(actual).isEqualTo(Pair(first, second))
  }
}
