package iterator.nucleus.accounts

import iterator.nucleus.parameters.ClassificationCode
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class StubAccountingCodeResolver : AccountingCodeResolver {
  private val stubbed: MutableMap<String, String> = ConcurrentHashMap()

  fun stub(
    classificationCode: String,
    accountingCode: String,
  ) {
    stubbed[classificationCode] = accountingCode
  }

  fun clear() = stubbed.clear()

  override fun resolve(
    classificationCode: ClassificationCode,
    at: Instant,
  ): String? = stubbed[classificationCode.value]
}

@TestConfiguration
class StubAccountingCodeResolverConfig {
  @Bean
  @Primary
  fun stubAccountingCodeResolver(): StubAccountingCodeResolver = StubAccountingCodeResolver()
}
