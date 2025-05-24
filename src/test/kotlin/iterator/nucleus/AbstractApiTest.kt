package iterator.nucleus

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api-test")
abstract class AbstractApiTest(
  val ctx: GenericApplicationContext,
  val mvc: MockMvc,
)
