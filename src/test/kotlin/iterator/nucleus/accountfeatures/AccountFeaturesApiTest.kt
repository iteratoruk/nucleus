package iterator.nucleus.accountfeatures

import iterator.nucleus.AbstractApiTest
import iterator.nucleus.audit.NucleusAuditEventType
import iterator.nucleus.parameters.NodeCreatedEvent
import iterator.nucleus.parameters.ParameterValueSetEvent
import iterator.nucleus.parameters.ParameterValueSupersededEvent
import iterator.nucleus.withHeaders
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.time.Instant
import java.util.UUID

class AccountFeaturesApiTest
  @Autowired
  constructor(
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractApiTest(ctx, mvc) {
    @Autowired lateinit var processingBoundaryClosureRepository: ProcessingBoundaryClosureRepository

    @Test
    fun `configuration submitted with a past effective datetime in an open period is immediately applicable`() {
      assertThat(processingBoundaryClosureRepository.count()).isZero()

      givenNodeExists("LIAB")

      mvc
        .put("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron", "NUC6-S2")
          contentType = MediaType.APPLICATION_JSON
          content =
            """
            {
              "effectiveDatetime": "2026-03-01T00:00:00Z",
              "features": {
                "liabilityInterest": {
                  "enabled": true,
                  "interestRate": "0.0350000"
                }
              }
            }
            """.trimIndent()
        }.andExpect { status { isOk() } }

      mvc
        .get("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron")
          param("asAt", "2026-03-01T00:00:00Z")
        }.andExpect {
          status { isOk() }
          jsonPath("$.features.liabilityInterest.enabled") { value(true) }
          jsonPath("$.features.liabilityInterest.interestRate") { value("0.0350000") }
        }

      mvc
        .get("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron")
          param("asAt", "2026-03-20T12:00:00Z")
        }.andExpect {
          status { isOk() }
          jsonPath("$.features.liabilityInterest.enabled") { value(true) }
          jsonPath("$.features.liabilityInterest.interestRate") { value("0.0350000") }
        }

      val parameterValueSetEvents =
        mockAuditService
          .getAuditEvents(NucleusAuditEventType.PARAMETER_VALUE_SET)
          .filterIsInstance<ParameterValueSetEvent>()
          .filter { it.classificationCode == "LIAB_INAS_2026" }
      assertThat(parameterValueSetEvents).hasSize(2)
      assertThat(parameterValueSetEvents).anySatisfy { event ->
        assertThat(event.parameterKey).isEqualTo("liabilityInterest.enabled")
        assertThat(event.effectiveDatetime).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"))
      }
      assertThat(parameterValueSetEvents).anySatisfy { event ->
        assertThat(event.parameterKey).isEqualTo("liabilityInterest.interestRate")
        assertThat(event.effectiveDatetime).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"))
      }
    }

    @Test
    fun `a new classification code is registered with account feature configuration`() {
      givenNodeExists("LIAB")

      mvc
        .put("/api/v1/account-features/LIAB_INAS") {
          withHeaders("test-configurer", "NUC1-T1")
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

    @Test
    fun `intermediate ancestor nodes are created as empty nodes when registering a deep classification code`() {
      givenNodeExists("LIAB")

      mvc
        .put("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("test-configurer", "NUC2-T1")
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

      val nodeCreatedEvents =
        mockAuditService
          .getAuditEvents(NucleusAuditEventType.NODE_CREATED)
          .filterIsInstance<NodeCreatedEvent>()

      assertThat(nodeCreatedEvents.map { it.classificationCode })
        .contains("LIAB_INAS", "LIAB_INAS_2026")

      val parameterValueSetEvents =
        mockAuditService
          .getAuditEvents(NucleusAuditEventType.PARAMETER_VALUE_SET)
          .filterIsInstance<ParameterValueSetEvent>()

      assertThat(parameterValueSetEvents.filter { it.classificationCode == "LIAB_INAS" }).isEmpty()
      assertThat(parameterValueSetEvents.filter { it.classificationCode == "LIAB_INAS_2026" })
        .hasSize(2)
      assertThat(parameterValueSetEvents).anySatisfy { event ->
        assertThat(event.classificationCode).isEqualTo("LIAB_INAS_2026")
        assertThat(event.parameterKey).isEqualTo("liabilityInterest.enabled")
        assertThat(event.value).isEqualTo("true")
        assertThat(event.effectiveDatetime).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"))
      }
      assertThat(parameterValueSetEvents).anySatisfy { event ->
        assertThat(event.classificationCode).isEqualTo("LIAB_INAS_2026")
        assertThat(event.parameterKey).isEqualTo("liabilityInterest.interestRate")
        assertThat(event.value).isEqualTo("0.0350000")
        assertThat(event.effectiveDatetime).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"))
      }
    }

    @Test
    fun `a submission containing a feature not valid for the ledger side is rejected`() {
      mvc
        .put("/api/v1/account-features/LIAB_INAS") {
          withHeaders("cameron", "NUC3-T1")
          contentType = MediaType.APPLICATION_JSON
          content =
            """
            {
              "effectiveDatetime": "2026-04-01T00:00:00Z",
              "features": {
                "assetInterest": {
                  "enabled": true,
                  "interestRate": "0.0350000"
                }
              }
            }
            """.trimIndent()
        }.andExpect {
          status { isBadRequest() }
          jsonPath("$.code") { value("INVALID_FEATURE_CONFIGURATION") }
          jsonPath("$.violations") { value(hasSize<Any>(1)) }
          jsonPath("$.violations[0].subject") { value("assetInterest") }
          jsonPath("$.violations[0].message") { value(containsString("LIAB")) }
        }

      assertThat(mockAuditService.getAuditEvents(NucleusAuditEventType.NODE_CREATED)).isEmpty()
      assertThat(mockAuditService.getAuditEvents(NucleusAuditEventType.PARAMETER_VALUE_SET)).isEmpty()
    }

    @Test
    fun `a submission targeting a malformed classification code is rejected`() {
      mvc
        .put("/api/v1/account-features/LIAB-INAS") {
          withHeaders("cameron", "NUC3-T2")
          contentType = MediaType.APPLICATION_JSON
          content =
            """
            {
              "effectiveDatetime": "2026-04-01T00:00:00Z",
              "features": {
                "liabilityInterest": { "enabled": true }
              }
            }
            """.trimIndent()
        }.andExpect {
          status { isBadRequest() }
          jsonPath("$.code") { value("INVALID_FEATURE_CONFIGURATION") }
          jsonPath("$.violations") { value(hasSize<Any>(1)) }
          jsonPath("$.violations[0].subject") { value(containsString("LIAB-INAS")) }
          jsonPath("$.violations[0].message") { value(containsString("classification code")) }
        }
    }

    @Test
    fun `a submission containing an interest rate with more than 7 decimal places is rejected`() {
      mvc
        .put("/api/v1/account-features/LIAB_INAS") {
          withHeaders("cameron", "NUC3-T3")
          contentType = MediaType.APPLICATION_JSON
          content =
            """
            {
              "effectiveDatetime": "2026-04-01T00:00:00Z",
              "features": {
                "liabilityInterest": {
                  "interestRate": "0.05000004"
                }
              }
            }
            """.trimIndent()
        }.andExpect {
          status { isBadRequest() }
          jsonPath("$.code") { value("INVALID_FEATURE_CONFIGURATION") }
          jsonPath("$.violations") { value(hasSize<Any>(1)) }
          jsonPath("$.violations[0].subject") { value("liabilityInterest") }
          jsonPath("$.violations[0].message") { value(containsString("interestRate")) }
        }

      assertThat(mockAuditService.getAuditEvents(NucleusAuditEventType.NODE_CREATED)).isEmpty()
      assertThat(mockAuditService.getAuditEvents(NucleusAuditEventType.PARAMETER_VALUE_SET)).isEmpty()
    }

    @Test
    fun `a submission with multiple invalid features reports all violations`() {
      mvc
        .put("/api/v1/account-features/LIAB_INAS") {
          withHeaders("cameron", "NUC3-T4")
          contentType = MediaType.APPLICATION_JSON
          content =
            """
            {
              "effectiveDatetime": "2026-04-01T00:00:00Z",
              "features": {
                "assetInterest": { "enabled": true },
                "liabilityInterest": { "interestRate": "0.05000004" }
              }
            }
            """.trimIndent()
        }.andExpect {
          status { isBadRequest() }
          jsonPath("$.code") { value("INVALID_FEATURE_CONFIGURATION") }
          jsonPath("$.violations") { value(hasSize<Any>(2)) }
          jsonPath("$.violations[*].subject") {
            value(containsInAnyOrder("assetInterest", "liabilityInterest"))
          }
        }

      assertThat(mockAuditService.getAuditEvents(NucleusAuditEventType.NODE_CREATED)).isEmpty()
      assertThat(mockAuditService.getAuditEvents(NucleusAuditEventType.PARAMETER_VALUE_SET)).isEmpty()
    }

    @Test
    fun `re-submission with the same idempotency key is accepted and the original configuration is preserved`() {
      givenNodeExists("LIAB")

      mvc
        .put("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron", "NUC4-S1")
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
        }.andExpect { status { isOk() } }

      mockAuditService.clear()

      mvc
        .put("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron", "NUC4-S1")
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

      assertThat(mockAuditService.getAuditEvents(NucleusAuditEventType.NODE_CREATED)).isEmpty()
      assertThat(mockAuditService.getAuditEvents(NucleusAuditEventType.PARAMETER_VALUE_SET)).isEmpty()
    }

    @Test
    fun `re-submission with the same idempotency key and a different payload is a no-op`() {
      givenNodeExists("LIAB")

      mvc
        .put("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron", "NUC4-S2")
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
        }.andExpect { status { isOk() } }

      mockAuditService.clear()

      mvc
        .put("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron", "NUC4-S2")
          contentType = MediaType.APPLICATION_JSON
          content =
            """
            {
              "effectiveDatetime": "2026-04-01T00:00:00Z",
              "features": {
                "liabilityInterest": {
                  "enabled": true,
                  "interestRate": "0.0500000"
                }
              }
            }
            """.trimIndent()
        }.andExpect {
          status { isOk() }
          jsonPath("$.features.liabilityInterest.interestRate") { value("0.0350000") }
        }

      assertThat(mockAuditService.getAuditEvents(NucleusAuditEventType.PARAMETER_VALUE_SET)).isEmpty()
    }

    @Test
    fun `re-submission with the same idempotency key against a different classification code is a no-op`() {
      givenNodeExists("LIAB")

      mvc
        .put("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron", "NUC4-S3")
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
        }.andExpect { status { isOk() } }

      mockAuditService.clear()

      mvc
        .put("/api/v1/account-features/LIAB_INAS_2027") {
          withHeaders("cameron", "NUC4-S3")
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
        }.andExpect { status { isOk() } }

      assertThat(
        mockAuditService
          .getAuditEvents(NucleusAuditEventType.NODE_CREATED)
          .filterIsInstance<NodeCreatedEvent>()
          .map { it.classificationCode },
      ).doesNotContain("LIAB_INAS_2027")

      assertThat(mockAuditService.getAuditEvents(NucleusAuditEventType.PARAMETER_VALUE_SET)).isEmpty()
    }

    @Test
    fun `a revised value for the same effective datetime supersedes the previously active value`() {
      givenNodeExists("LIAB")

      mvc
        .put("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron", "NUC5-S1-setup")
          contentType = MediaType.APPLICATION_JSON
          content =
            """
            {
              "effectiveDatetime": "2026-04-01T00:00:00Z",
              "features": {
                "liabilityInterest": { "interestRate": "0.0350000" }
              }
            }
            """.trimIndent()
        }.andExpect { status { isOk() } }

      mockAuditService.clear()

      mvc
        .put("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron", "NUC5-S1")
          contentType = MediaType.APPLICATION_JSON
          content =
            """
            {
              "effectiveDatetime": "2026-04-01T00:00:00Z",
              "features": {
                "liabilityInterest": { "interestRate": "0.0400000" }
              }
            }
            """.trimIndent()
        }.andExpect {
          status { isOk() }
          jsonPath("$.features.liabilityInterest.interestRate") { value("0.0400000") }
        }

      assertThat(mockAuditService.getAuditEvents(NucleusAuditEventType.NODE_CREATED)).isEmpty()

      val setEvents =
        mockAuditService
          .getAuditEvents(NucleusAuditEventType.PARAMETER_VALUE_SET)
          .filterIsInstance<ParameterValueSetEvent>()
      assertThat(setEvents).hasSize(1)
      val setEvent = setEvents.single()
      assertThat(setEvent.parameterKey).isEqualTo("liabilityInterest.interestRate")
      assertThat(setEvent.value).isEqualTo("0.0400000")
      assertThat(setEvent.priorValue).isEqualTo("0.0350000")
      assertThat(setEvent.effectiveDatetime).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"))

      val supersededEvents =
        mockAuditService
          .getAuditEvents(NucleusAuditEventType.PARAMETER_VALUE_SUPERSEDED)
          .filterIsInstance<ParameterValueSupersededEvent>()
      assertThat(supersededEvents).hasSize(1)
      val supersededEvent = supersededEvents.single()
      assertThat(supersededEvent.classificationCode).isEqualTo("LIAB_INAS_2026")
      assertThat(supersededEvent.parameterKey).isEqualTo("liabilityInterest.interestRate")
      assertThat(supersededEvent.effectiveDatetime).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"))
      assertThat(supersededEvent.supersededValue).isEqualTo("0.0350000")
    }

    @Test
    fun `configuration submitted with a future effective datetime does not govern resolution before the effective datetime`() {
      givenNodeExists("LIAB")

      mvc
        .put("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron", "NUC6-S1")
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
        }.andExpect { status { isOk() } }

      mvc
        .get("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron")
          param("asAt", "2026-03-20T12:00:00Z")
        }.andExpect {
          status { isOk() }
          jsonPath("$.features.liabilityInterest") { doesNotExist() }
        }

      mvc
        .get("/api/v1/account-features/LIAB_INAS_2026") {
          withHeaders("cameron")
          param("asAt", "2026-04-01T00:00:00Z")
        }.andExpect {
          status { isOk() }
          jsonPath("$.features.liabilityInterest.enabled") { value(true) }
          jsonPath("$.features.liabilityInterest.interestRate") { value("0.0350000") }
        }

      val nodeCreatedEvents =
        mockAuditService
          .getAuditEvents(NucleusAuditEventType.NODE_CREATED)
          .filterIsInstance<NodeCreatedEvent>()
      assertThat(nodeCreatedEvents.map { it.classificationCode }).contains("LIAB_INAS_2026")

      val parameterValueSetEvents =
        mockAuditService
          .getAuditEvents(NucleusAuditEventType.PARAMETER_VALUE_SET)
          .filterIsInstance<ParameterValueSetEvent>()
          .filter { it.classificationCode == "LIAB_INAS_2026" }
      assertThat(parameterValueSetEvents).hasSize(2)
      assertThat(parameterValueSetEvents).anySatisfy { event ->
        assertThat(event.parameterKey).isEqualTo("liabilityInterest.enabled")
        assertThat(event.effectiveDatetime).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"))
      }
      assertThat(parameterValueSetEvents).anySatisfy { event ->
        assertThat(event.parameterKey).isEqualTo("liabilityInterest.interestRate")
        assertThat(event.effectiveDatetime).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"))
      }
    }

    private fun givenNodeExists(classificationCode: String) {
      mvc
        .put("/api/v1/account-features/$classificationCode") {
          withHeaders("test-configurer", "setup-$classificationCode-${UUID.randomUUID()}")
          contentType = MediaType.APPLICATION_JSON
          content = """{ "effectiveDatetime": "2026-01-01T00:00:00Z", "features": {} }"""
        }.andExpect { status { isOk() } }
    }
  }
