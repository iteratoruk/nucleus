package iterator.nucleus

import iterator.nucleus.audit.AuditService
import iterator.nucleus.audit.MockAuditService
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.event.ApplicationEventMulticaster
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.Executor

fun MockHttpServletRequestDsl.withHeaders(
  clientId: String,
  idempotencyKey: String? = null,
) {
  header(NucleusHeaders.CLIENT_ID, clientId)
  idempotencyKey?.let { header(NucleusHeaders.IDEMPOTENCY_KEY, it) }
}

@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
@ActiveProfiles(ApiTestConstants.PROFILE_NAME)
@Sql("/clean.sql")
abstract class AbstractApiTest(
  val ctx: GenericApplicationContext,
  val mvc: MockMvc,
) : TestContainers {
  @Autowired lateinit var auditService: AuditService

  val mockAuditService: MockAuditService
    get() = auditService as MockAuditService

  @BeforeEach
  fun resetAuditServiceMock() {
    mockAuditService.clear()
  }
}

@EnableAsync
@Configuration
@Profile(ApiTestConstants.PROFILE_NAME)
class TestAsyncConfig : AsyncConfigurer {
  override fun getAsyncExecutor(): Executor = SyncTaskExecutor()

  @Bean
  fun applicationEventMulticaster(beanFactory: BeanFactory): ApplicationEventMulticaster = SimpleApplicationEventMulticaster(beanFactory)

  @Bean
  fun auditService(publisher: ApplicationEventPublisher): AuditService = MockAuditService(publisher)
}

object ApiTestConstants {
  const val PROFILE_NAME = "api-test"
}
