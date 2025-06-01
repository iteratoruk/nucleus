package iterator.nucleus.customer

import iterator.nucleus.AbstractJpaRepositoryTest
import iterator.nucleus.TestingFu.aValidCustomerTranche
import iterator.nucleus.TestingFu.randomWords
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.web.servlet.MockMvc
import java.util.UUID

class CustomerTrancheRepositoryTest
  @Autowired
  constructor(
    repo: CustomerTrancheRepository,
    em: EntityManager,
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractJpaRepositoryTest<CustomerTranche, CustomerTrancheRepository>(repo, em, ctx, mvc) {
    override fun randomValidEntity(): CustomerTranche = aValidCustomerTranche()

    override fun entityClass(): Class<CustomerTranche> = CustomerTranche::class.java

    override fun mutateEntity(entity: CustomerTranche) {
      entity.customerTrancheId = UUID.randomUUID()
      entity.displayName = randomWords(5)
    }
  }
