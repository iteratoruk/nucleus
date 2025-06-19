package iterator.nucleus

import iterator.nucleus.audit.AuditService
import org.springframework.beans.factory.BeanFactory
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.Executor

@SpringBootTest
@Testcontainers
@AutoConfigureMockMvc
@ActiveProfiles(ApiTestConstants.PROFILE_NAME)
abstract class AbstractApiTest(
  val ctx: GenericApplicationContext,
  val mvc: MockMvc,
) : TestContainers {
  @MockitoBean lateinit var auditService: AuditService
}

@EnableAsync
@Configuration
@Profile(ApiTestConstants.PROFILE_NAME)
class TestAsyncConfig : AsyncConfigurer {
  override fun getAsyncExecutor(): Executor = SyncTaskExecutor()

  @Bean
  fun applicationEventMulticaster(beanFactory: BeanFactory): ApplicationEventMulticaster = SimpleApplicationEventMulticaster(beanFactory)
}

object ApiTestConstants {
  const val PROFILE_NAME = "api-test"
}
