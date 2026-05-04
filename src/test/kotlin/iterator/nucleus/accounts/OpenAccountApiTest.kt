package iterator.nucleus.accounts

import iterator.nucleus.AbstractApiTest
import iterator.nucleus.Serialization
import iterator.nucleus.audit.GenericAuditEvent
import iterator.nucleus.audit.NucleusAuditEventType
import iterator.nucleus.parameters.LedgerSide
import iterator.nucleus.withHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.context.support.GenericApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration

@Import(FixedClockConfig::class, StubAccountingCodeResolverConfig::class)
class OpenAccountApiTest
  @Autowired
  constructor(
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractApiTest(ctx, mvc) {
    @Autowired lateinit var accountRepository: AccountRepository

    @Autowired lateinit var stubAccountingCodeResolver: StubAccountingCodeResolver

    @BeforeEach
    fun stubAccountingCode() {
      stubAccountingCodeResolver.clear()
      stubAccountingCodeResolver.stub("LIAB_INAS_2026", "LIAB_RETL_SAVE")
    }

    @Test
    fun `opens an account and returns an account identifier`() {
      mvc
        .post("/api/v1/accounts") {
          withHeaders("cameron", "IK-A-001")
          contentType = MediaType.APPLICATION_JSON
          content =
            """
            {
              "classificationCode": "LIAB_INAS_2026",
              "stakeholderIdentifier": "STK-1001"
            }
            """.trimIndent()
        }.andExpect {
          status { isCreated() }
          jsonPath("$.accountIdentifier") { isNotEmpty() }
        }
    }

    @Test
    fun `an opened account has status OPEN`() {
      mvc
        .post("/api/v1/accounts") {
          withHeaders("cameron", "IK-A-002")
          contentType = MediaType.APPLICATION_JSON
          content =
            """
            {
              "classificationCode": "LIAB_INAS_2026",
              "stakeholderIdentifier": "STK-1001"
            }
            """.trimIndent()
        }.andExpect {
          status { isCreated() }
          jsonPath("$.status") { value("OPEN") }
        }
    }

    @Test
    fun `an opened account is persisted with the submitted classification code and stakeholder identifier and the derived ledger side`() {
      val result =
        mvc
          .post("/api/v1/accounts") {
            withHeaders("cameron", "IK-A-003")
            contentType = MediaType.APPLICATION_JSON
            content =
              """
              {
                "classificationCode": "LIAB_INAS_2026",
                "stakeholderIdentifier": "STK-1001"
              }
              """.trimIndent()
          }.andExpect { status { isCreated() } }
          .andReturn()

      val response =
        Serialization.mapper.readValue(
          result.response.contentAsString,
          OpenAccountResponse::class.java,
        )

      val persisted = accountRepository.findByAccountIdentifier(response.accountIdentifier)
      assertThat(persisted).isNotNull
      assertThat(persisted!!.classificationCode).isEqualTo("LIAB_INAS_2026")
      assertThat(persisted.stakeholderIdentifier).isEqualTo("STK-1001")
      assertThat(persisted.ledgerSide).isEqualTo(LedgerSide.LIAB)
    }

    @Test
    fun `opening an account publishes an AccountOpened event with the assigned identifier and resolved configurer attribution`() {
      val result =
        mvc
          .post("/api/v1/accounts") {
            withHeaders("cameron", "IK-A-004")
            contentType = MediaType.APPLICATION_JSON
            content =
              """
              {
                "classificationCode": "LIAB_INAS_2026",
                "stakeholderIdentifier": "STK-1001"
              }
              """.trimIndent()
          }.andExpect { status { isCreated() } }
          .andReturn()

      val response =
        Serialization.mapper.readValue(
          result.response.contentAsString,
          OpenAccountResponse::class.java,
        )

      val events =
        outboundEventCollector
          .eventsOf(
            AccountTopics.ACCOUNT_OPENED,
            AccountOpened::class.java,
            Duration.ofSeconds(10),
          ).filter { it.accountIdentifier == response.accountIdentifier }
      assertThat(events).hasSize(1)
      val event = events.first()
      assertThat(event.stakeholderIdentifier).isEqualTo("STK-1001")
      assertThat(event.classificationCode).isEqualTo("LIAB_INAS_2026")
      assertThat(event.ledgerSide).isEqualTo(LedgerSide.LIAB)
      assertThat(event.openedBy).isEqualTo("cameron")
      assertThat(event.openingTimestamp).isEqualTo(FixedClockConfig.FIXED_INSTANT)
      assertThat(event.accountingCode).isEqualTo("LIAB_RETL_SAVE")
    }

    @Test
    fun `opening an account also publishes a corresponding ACCOUNT_OPENED audit event`() {
      val result =
        mvc
          .post("/api/v1/accounts") {
            withHeaders("cameron", "IK-A-005")
            contentType = MediaType.APPLICATION_JSON
            content =
              """
              {
                "classificationCode": "LIAB_INAS_2026",
                "stakeholderIdentifier": "STK-1001"
              }
              """.trimIndent()
          }.andExpect { status { isCreated() } }
          .andReturn()

      val response =
        Serialization.mapper.readValue(
          result.response.contentAsString,
          OpenAccountResponse::class.java,
        )

      val auditEvents =
        mockAuditService
          .getAuditEvents(NucleusAuditEventType.ACCOUNT_OPENED)
          .filterIsInstance<GenericAuditEvent>()
      assertThat(auditEvents).hasSize(1)
      val auditEvent = auditEvents.first()
      assertThat(auditEvent.principal).isEqualTo("cameron")
      assertThat(auditEvent.timestamp).isEqualTo(FixedClockConfig.FIXED_INSTANT)
      assertThat(auditEvent.data)
        .containsEntry("accountIdentifier", response.accountIdentifier.toString())
        .containsEntry("stakeholderIdentifier", "STK-1001")
        .containsEntry("ledgerSide", "LIAB")
        .containsEntry("classificationCode", "LIAB_INAS_2026")
        .containsEntry("accountingCode", "LIAB_RETL_SAVE")
        .containsEntry("openingTimestamp", FixedClockConfig.FIXED_INSTANT.toString())
    }

    @Test
    fun `re-submission with the same idempotency key returns the original opening response and creates no second account or event`() {
      val body =
        """
        {
          "classificationCode": "LIAB_INAS_2026",
          "stakeholderIdentifier": "STK-1001"
        }
        """.trimIndent()

      val firstResult =
        mvc
          .post("/api/v1/accounts") {
            withHeaders("cameron", "IK-A-006")
            contentType = MediaType.APPLICATION_JSON
            content = body
          }.andExpect { status { isCreated() } }
          .andReturn()
      val firstResponse =
        Serialization.mapper.readValue(
          firstResult.response.contentAsString,
          OpenAccountResponse::class.java,
        )

      val secondResult =
        mvc
          .post("/api/v1/accounts") {
            withHeaders("cameron", "IK-A-006")
            contentType = MediaType.APPLICATION_JSON
            content = body
          }.andExpect { status { isCreated() } }
          .andReturn()
      val secondResponse =
        Serialization.mapper.readValue(
          secondResult.response.contentAsString,
          OpenAccountResponse::class.java,
        )

      assertThat(secondResponse.accountIdentifier).isEqualTo(firstResponse.accountIdentifier)
      assertThat(accountRepository.findAll()).hasSize(1)

      val events =
        outboundEventCollector
          .eventsOf(
            AccountTopics.ACCOUNT_OPENED,
            AccountOpened::class.java,
            Duration.ofSeconds(10),
          ).filter { it.accountIdentifier == firstResponse.accountIdentifier }
      assertThat(events).hasSize(1)

      val auditEvents = mockAuditService.getAuditEvents(NucleusAuditEventType.ACCOUNT_OPENED)
      assertThat(auditEvents).hasSize(1)
    }
  }
