package iterator.nucleus.accountfeatures

import iterator.nucleus.AbstractApiTest
import iterator.nucleus.NucleusHeaders
import iterator.nucleus.audit.MockAuditService
import iterator.nucleus.audit.NucleusAuditEventType
import iterator.nucleus.parameters.NodeCreatedEvent
import iterator.nucleus.parameters.ParameterValueSetEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.put
import java.time.Instant

class AccountFeaturesApiTest
  @Autowired
  constructor(
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractApiTest(ctx, mvc) {
    @Test
    fun `a new classification code is registered with account feature configuration`() {
      givenNodeExists("LIAB")

      mvc
        .put("/api/v1/account-features/LIAB_INAS") {
          header(NucleusHeaders.CLIENT_ID, "test-configurer")
          contentType = MediaType.APPLICATION_JSON
          content =
            """
            {
              "effectiveDatetime": "2026-04-01T00:00:00Z",
              "features": {
                "liabilityInterest": {
                  "enabled": true,
                  "interestRate": "0.0350000"
                }
              }
            }
            """.trimIndent()
        }.andExpect {
          status { isOk() }
          jsonPath("$.features.liabilityInterest.enabled") { value(true) }
          jsonPath("$.features.liabilityInterest.interestRate") { value("0.0350000") }
        }

      val mockAuditService = auditService as MockAuditService

      val nodeCreatedEvents =
        mockAuditService
          .getAuditEvents(NucleusAuditEventType.NODE_CREATED)
          .filterIsInstance<NodeCreatedEvent>()
          .filter { it.classificationCode == "LIAB_INAS" }
      assertThat(nodeCreatedEvents).hasSize(1)

      val parameterValueSetEvents =
        mockAuditService
          .getAuditEvents(NucleusAuditEventType.PARAMETER_VALUE_SET)
          .filterIsInstance<ParameterValueSetEvent>()
          .filter { it.classificationCode == "LIAB_INAS" }
      assertThat(parameterValueSetEvents).hasSize(2)
      assertThat(parameterValueSetEvents).anySatisfy { event ->
        assertThat(event.parameterKey).isEqualTo("liabilityInterest.enabled")
        assertThat(event.value).isEqualTo("true")
        assertThat(event.effectiveDatetime).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"))
      }
      assertThat(parameterValueSetEvents).anySatisfy { event ->
        assertThat(event.parameterKey).isEqualTo("liabilityInterest.interestRate")
        assertThat(event.value).isEqualTo("0.0350000")
        assertThat(event.effectiveDatetime).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"))
      }
    }

    private fun givenNodeExists(classificationCode: String) {
      mvc
        .put("/api/v1/account-features/$classificationCode") {
          header(NucleusHeaders.CLIENT_ID, "test-configurer")
          contentType = MediaType.APPLICATION_JSON
          content = """{ "effectiveDatetime": "2026-01-01T00:00:00Z", "features": {} }"""
        }.andExpect { status { isOk() } }
    }
  }
