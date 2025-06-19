package iterator.nucleus.account.feature

import com.fasterxml.jackson.databind.ObjectMapper
import iterator.nucleus.AbstractApiTest
import iterator.nucleus.TestingFu.randomBigDecimal
import iterator.nucleus.TestingFu.randomUUID
import iterator.nucleus.TestingFu.randomWords
import iterator.nucleus.account.Account
import iterator.nucleus.account.AccountConstants
import iterator.nucleus.account.AccountFeature
import iterator.nucleus.account.AccountFeatureRepository
import iterator.nucleus.account.AccountRepository
import iterator.nucleus.account.AccountStatus
import iterator.nucleus.account.InternalAccountRole
import iterator.nucleus.account.template.AccountTemplate
import iterator.nucleus.account.template.AccountTemplateRepository
import iterator.nucleus.audit.AbstractAccountLevelAuditEvent
import iterator.nucleus.audit.AbstractAuditEvent
import iterator.nucleus.audit.NucleusAuditEventType
import iterator.nucleus.audit.ScheduledTaskFinishedEvent
import iterator.nucleus.customer.CustomerTranche
import iterator.nucleus.customer.CustomerTrancheRepository
import iterator.nucleus.ledger.LedgerConstants
import iterator.nucleus.ledger.LedgerEntry
import iterator.nucleus.ledger.LedgerEntryService
import iterator.nucleus.parameter.ParameterDefinition
import iterator.nucleus.parameter.ParameterDefinitionRepository
import iterator.nucleus.parameter.ParameterLevel
import iterator.nucleus.parameter.ParameterValue
import iterator.nucleus.parameter.ParameterValueRepository
import iterator.nucleus.schedule.ScheduledTask
import iterator.nucleus.schedule.ScheduledTaskStatus
import iterator.nucleus.toSevenDecimalPlaces
import org.assertj.core.api.Assertions
import org.awaitility.kotlin.await
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.quartz.JobDataMap
import org.quartz.JobKey
import org.quartz.Scheduler
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.GenericApplicationContext
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockMvc
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Suppress("FunctionName")
@Sql(scripts = ["classpath:clean.sql"], executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
abstract class AbstractFeaturePipelineIntegrationTest
  @Autowired
  constructor(
    ctx: GenericApplicationContext,
    mvc: MockMvc,
  ) : AbstractApiTest(ctx, mvc) {
    @Autowired lateinit var accountRepo: AccountRepository

    @Autowired lateinit var accountFeatureRepo: AccountFeatureRepository

    @Autowired lateinit var accountTemplateRepo: AccountTemplateRepository

    @Autowired lateinit var customerTrancheRepo: CustomerTrancheRepository

    @Autowired lateinit var parameterDefinitionRepo: ParameterDefinitionRepository

    @Autowired lateinit var parameterValueRepo: ParameterValueRepository

    @Autowired lateinit var ledgerService: LedgerEntryService

    @Autowired lateinit var scheduler: Scheduler

    @Autowired lateinit var om: ObjectMapper

    lateinit var internalAccounts: Map<InternalAccountRole, Account>

    lateinit var accountTemplate: AccountTemplate

    lateinit var internalAccountTemplate: AccountTemplate

    @BeforeEach
    fun setupAccountsAndTemplates() {
      accountTemplate = `create account template`()
      internalAccountTemplate = `create account template`()
      internalAccounts = `create internal accounts`(internalAccountTemplate)
      reset(auditService)
    }

    fun `create internal accounts`(template: AccountTemplate): Map<InternalAccountRole, Account> =
      InternalAccountRole.entries
        .map {
          accountRepo.save(
            Account(
              accountId = UUID.randomUUID(),
              customerId = AccountConstants.INTERNAL_BANK_CUSTOMER_ID,
              status = AccountStatus.OPEN,
              internal = true,
              internalAccountRole = it,
              accountTemplate = template,
            ),
          )
        }.associateBy { it.internalAccountRole!! }

    fun `create account template`(
      accountTemplateId: String = randomUUID(),
      displayName: String = randomWords(3),
    ): AccountTemplate =
      accountTemplateRepo.save(
        AccountTemplate(accountTemplateId = accountTemplateId, displayName = displayName),
      )

    fun `create customer account`(
      template: AccountTemplate = accountTemplate,
      accountId: UUID = UUID.randomUUID(),
      customerId: String = randomUUID(),
      status: AccountStatus = AccountStatus.OPEN,
    ): Account =
      accountRepo.save(
        Account(
          accountId = accountId,
          customerId = customerId,
          status = status,
          internal = false,
          accountTemplate = template,
        ),
      )

    fun `fund customer account`(
      account: Account,
      fromAccount: Account,
      balance: BigDecimal = randomBigDecimal(100.00, 10000.00).toSevenDecimalPlaces(),
      timestamp: Instant,
    ): List<LedgerEntry> =
      ledgerService.createTransfer(
        fromAccount = fromAccount,
        fromAddress = LedgerConstants.DEFAULT_ADDRESS,
        toAccount = account,
        toAddress = LedgerConstants.DEFAULT_ADDRESS,
        amount = balance,
        timestamp = timestamp,
      )

    fun `create account feature`(name: String): AccountFeature = accountFeatureRepo.save(AccountFeature(name = name))

    fun `enable feature for account`(
      account: Account,
      feature: AccountFeature,
    ): Account {
      account.features.add(feature)
      return accountRepo.save(account)
    }

    fun `create customer tranche`(displayName: String = randomWords(3)): CustomerTranche =
      customerTrancheRepo.save(
        CustomerTranche(
          customerTrancheId = UUID.randomUUID(),
          displayName = displayName,
        ),
      )

    fun `associate account with customer tranche`(
      account: Account,
      tranche: CustomerTranche,
    ): Account {
      account.customerTranche = tranche
      return accountRepo.save(account)
    }

    fun `create parameter definition`(
      name: String,
      displayName: String = randomWords(3),
      description: String = randomWords(8),
    ): ParameterDefinition =
      parameterDefinitionRepo.save(
        ParameterDefinition(
          name = name,
          displayName = displayName,
          description = description,
        ),
      )

    fun `create parameter value`(
      definition: ParameterDefinition,
      value: String,
      level: ParameterLevel = ParameterLevel.GLOBAL,
      resourceId: String? = null,
      effectiveFrom: Instant,
      effectiveTo: Instant? = null,
    ): ParameterValue =
      parameterValueRepo.save(
        ParameterValue(
          definition = definition,
          level = level,
          resourceId = resourceId,
          value = value,
          effectiveFrom = effectiveFrom,
          effectiveTo = effectiveTo,
        ),
      )

    fun <D : Any, T : ScheduledTask<D>> `when I trigger the scheduled task`(
      taskClass: Class<T>,
      data: D,
    ) {
      scheduler.triggerJob(
        JobKey.jobKey(taskClass.name, "scheduledTasks"),
        JobDataMap(
          mapOf(
            "payloadClass" to data::class.java.name,
            "payload" to om.writeValueAsString(data),
          ),
        ),
      )
    }

    fun <T : ScheduledTask<*>> `then the scheduled task has finished with status`(
      task: Class<T>,
      status: ScheduledTaskStatus,
    ) {
      val taskFinishedEvents =
        `then an audit event is produced`(NucleusAuditEventType.SCHEDULED_TASK_FINISHED)
      val taskFinishedEvent =
        taskFinishedEvents.filterIsInstance<ScheduledTaskFinishedEvent>().firstOrNull {
          it.status == status && it.taskName == task.name
        }
      Assertions
        .assertThat(taskFinishedEvent)
        .describedAs("Task finished event not found for task: $task")
        .isNotNull
    }

    // this returns a list because there is always the possibility that we might have captured more
    // than 1
    fun `then an account-level audit event is produced`(
      accountId: UUID,
      type: NucleusAuditEventType,
      waitFor: Duration = Duration.ofSeconds(5),
    ): List<AbstractAccountLevelAuditEvent> {
      val captor = argumentCaptor<AbstractAuditEvent>()
      await.atMost(waitFor).untilAsserted {
        verify(auditService, atLeastOnce()).publishAuditEvent(captor.capture())
        Assertions
          .assertThat(captor.allValues.filter { findAccountLevelEvent(it, type, accountId) })
          .describedAs(
            "No account-level audit event found for account: $accountId and type: $type.",
          ).isNotEmpty
      }
      return captor.allValues
        .filter { findAccountLevelEvent(it, type, accountId) }
        .map { it as AbstractAccountLevelAuditEvent }
    }

    fun <T : AbstractAccountLevelAuditEvent> `then an account-level audit event is produced`(
      waitFor: Duration = Duration.ofSeconds(5),
      expected: T,
    ) {
      val actualEvents =
        `then an account-level audit event is produced`(
          accountId = expected.auditEvent.data["accountId"]!! as UUID,
          type = NucleusAuditEventType.valueOf(expected.auditEvent.type),
          waitFor = waitFor,
        )
      Assertions.assertThat(actualEvents).contains(expected)
    }

    fun `then an audit event is produced`(
      type: NucleusAuditEventType,
      waitFor: Duration = Duration.ofSeconds(5),
    ): List<AbstractAuditEvent> {
      val captor = argumentCaptor<AbstractAuditEvent>()
      await.atMost(waitFor).untilAsserted {
        verify(auditService, atLeastOnce()).publishAuditEvent(captor.capture())
        Assertions
          .assertThat(captor.allValues.filter { it.auditEvent.type == type.name })
          .describedAs("No audit event found for type: $type.")
          .isNotEmpty
      }
      return captor.allValues.filter { it.auditEvent.type == type.name }
    }

    fun `then account has balance for address and asset at timestamp`(
      accountId: UUID,
      timestamp: Instant,
      balance: BigDecimal,
      address: String = LedgerConstants.DEFAULT_ADDRESS,
      asset: String = LedgerConstants.DEFAULT_ASSET,
    ) {
      val actualBalance =
        ledgerService.findCommittedBalances(
          accountId = accountId,
          effectiveTimestamp = timestamp,
          addresses = setOf(address),
          asset = asset,
        )
      Assertions.assertThat(actualBalance[address]).isEqualTo(balance)
    }

    private fun findAccountLevelEvent(
      event: AbstractAuditEvent,
      type: NucleusAuditEventType,
      accountId: UUID,
    ): Boolean =
      event is AbstractAccountLevelAuditEvent &&
        event.auditEvent.type == type.name &&
        event.auditEvent.data["accountId"] == accountId
  }
