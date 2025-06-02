package iterator.nucleus.ledger

import iterator.nucleus.AbstractJpaRepositoryTest
import iterator.nucleus.TestingFu.aValidAccount
import iterator.nucleus.TestingFu.aValidAccountTemplate
import iterator.nucleus.TestingFu.aValidLedgerEntry
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.web.servlet.MockMvc

class LedgerEntryRepositoryTest
  @Autowired
  constructor(
    repo: LedgerEntryRepository,
    em: EntityManager,
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractJpaRepositoryTest<LedgerEntry, LedgerEntryRepository>(repo, em, ctx, mvc) {
    override fun randomValidEntity(): LedgerEntry {
      val accountTemplate = aValidAccountTemplate()
      val account = aValidAccount(accountTemplate)
      persistAndFlush(listOf(accountTemplate, account))
      return aValidLedgerEntry(account)
    }

    override fun entityClass(): Class<LedgerEntry> = LedgerEntry::class.java
  }
