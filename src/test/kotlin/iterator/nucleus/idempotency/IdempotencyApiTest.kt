package iterator.nucleus.idempotency

import iterator.nucleus.AbstractApiTest
import iterator.nucleus.ApiTestConstants
import iterator.nucleus.Serialization
import iterator.nucleus.TestingFu.randomAlphanumeric
import iterator.nucleus.Uris
import iterator.nucleus.withHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.context.support.GenericApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.atomic.AtomicInteger

class IdempotencyApiTest
  @Autowired
  constructor(
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractApiTest(ctx, mvc) {
    @Autowired lateinit var probeController: IdempotencyProbeController

    @BeforeEach
    fun resetInvocations() {
      probeController.invocations.set(0)
    }

    private fun submit(
      idempotencyKey: String?,
      value: String,
    ) = mvc.post("${Uris.API_V1}/idempotency-probe") {
      withHeaders(clientId = randomAlphanumeric(8), idempotencyKey = idempotencyKey)
      contentType = MediaType.APPLICATION_JSON
      content = Serialization.mapper.writeValueAsString(IdempotencyProbeRequest(value))
    }

    @Test
    fun `resubmitting with the same key runs the work once and replays the response`() {
      val key = randomAlphanumeric(24)

      val first =
        submit(key, "alpha")
          .andExpect { status { isOk() } }
          .andReturn()
          .response.contentAsString
      val second =
        submit(key, "alpha")
          .andExpect { status { isOk() } }
          .andReturn()
          .response.contentAsString

      assertThat(second).isEqualTo(first)
      assertThat(probeController.invocations.get()).isEqualTo(1)
    }

    @Test
    fun `a different key runs the work again`() {
      submit(randomAlphanumeric(24), "alpha").andExpect { status { isOk() } }
      submit(randomAlphanumeric(24), "alpha").andExpect { status { isOk() } }

      assertThat(probeController.invocations.get()).isEqualTo(2)
    }

    @Test
    fun `without an idempotency key the work runs each time`() {
      submit(idempotencyKey = null, value = "alpha").andExpect { status { isOk() } }
      submit(idempotencyKey = null, value = "alpha").andExpect { status { isOk() } }

      assertThat(probeController.invocations.get()).isEqualTo(2)
    }
  }

@RestController
@Profile(ApiTestConstants.PROFILE_NAME)
class IdempotencyProbeController {
  val invocations = AtomicInteger(0)

  @PostMapping("${Uris.API_V1}/idempotency-probe")
  @Idempotent
  fun probe(
    @RequestBody request: IdempotencyProbeRequest,
  ): IdempotencyProbeResponse = IdempotencyProbeResponse(request.value, invocations.incrementAndGet())
}

data class IdempotencyProbeRequest(
  val value: String,
)

data class IdempotencyProbeResponse(
  val value: String,
  val invocation: Int,
)
