package iterator.nucleus.account

import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.TestingFu.randomLong
import iterator.nucleus.TestingFu.validAccountsWithIds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.eq
import org.mockito.kotlin.given

@ExtendWith(MockitoExtension::class)
class AccountFeatureServiceTest(
  @Mock val repo: AccountFeatureRepository,
) {
  lateinit var service: AccountFeatureService

  @BeforeEach
  fun setup() {
    service = AccountFeatureService(repo)
  }

  @Test
  fun `should return false given zero count when is feature enabled`() {
    // given
    val name = randomAlphanumeric(16)
    val account = validAccountsWithIds(1, aValidAccountTemplate()).first()
    given { repo.countByNameAndAccountsContains(name = eq(name), account = eq(account)) }
      .willReturn(0)

    // then
    assertThat(service.isFeatureEnabled(name, account)).isFalse
  }

  @Test
  fun `should return true given greater than zero count when is feature enabled`() {
    // given
    val name = randomAlphanumeric(16)
    val account = validAccountsWithIds(1, aValidAccountTemplate()).first()
    given { repo.countByNameAndAccountsContains(name = eq(name), account = eq(account)) }
      .willReturn(randomLong(1, 1000000))

    // then
    assertThat(service.isFeatureEnabled(name, account)).isTrue
  }
}
