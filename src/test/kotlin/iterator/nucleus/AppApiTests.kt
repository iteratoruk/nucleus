package iterator.nucleus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class AppApiTests
  @Autowired
  constructor(
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractApiTest(ctx, mvc) {
    @Test
    fun `the application should start`() {
      assertThat(ctx.isRunning).isTrue
    }

    @Test
    fun `the application exposes a health endpoint`() {
      mvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.status").value("UP"))
    }
  }
