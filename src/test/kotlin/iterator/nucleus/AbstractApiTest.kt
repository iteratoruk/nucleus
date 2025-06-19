package iterator.nucleus

import iterator.nucleus.audit.AuditService
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
@ActiveProfiles("api-test")
abstract class AbstractApiTest(
  val ctx: GenericApplicationContext,
  val mvc: MockMvc,
) : TestContainers {
  @MockitoBean lateinit var auditService: AuditService
}
