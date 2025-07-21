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
import iterator.nucleus.audit.MockAuditService
import iterator.nucleus.audit.NucleusAuditEventType
import iterator.nucleus.audit.ScheduledTaskFinishedEvent
import iterator.nucleus.customer.CustomerTranche
import iterator.nucleus.customer.CustomerTrancheRepository
import iterator.nucleus.ledger.CreateTransferRequest
import iterator.nucleus.ledger.LedgerConstants
import iterator.nucleus.ledger.LedgerEntry
import iterator.nucleus.ledger.LedgerEntryService
import iterator.nucleus.ledger.LedgerEntryType
import iterator.nucleus.parameter.ParameterDefinition
import iterator.nucleus.parameter.ParameterDefinitionRepository
import iterator.nucleus.parameter.ParameterLevel
import iterator.nucleus.parameter.ParameterValue
import iterator.nucleus.parameter.ParameterValueRepository
import iterator.nucleus.schedule.ScheduledTask
import iterator.nucleus.schedule.ScheduledTaskStatus
import iterator.nucleus.toSevenDecimalPlaces
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.junit.jupiter.api.BeforeEach
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
    companion object {
      val DEFAULT_AWAIT_DURATION: Duration = Duration.ofSeconds(60)
      val DEFAULT_POLL_INTERVAL: Duration = Duration.ofMillis(100)
    }

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
        CreateTransferRequest(
          fromAccount = fromAccount,
          fromAddress = LedgerConstants.DEFAULT_ADDRESS,
          toAccount = account,
          toAddress = LedgerConstants.DEFAULT_ADDRESS,
          amount = balance,
          type = LedgerEntryType.ON_US,
          timestamp = timestamp,
        ),
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
      assertThat(taskFinishedEvent)
        .describedAs("Task finished event not found for task: $task")
        .isNotNull
    }

    // this returns a list because there is always the possibility that we might have captured more
    // than 1
    fun `then an account-level audit event is produced`(
      accountId: UUID,
      type: NucleusAuditEventType,
      waitFor: Duration = DEFAULT_AWAIT_DURATION,
      pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    ): List<AbstractAccountLevelAuditEvent> {
      var events = emptyList<AbstractAccountLevelAuditEvent>()
      await.atMost(waitFor).pollInterval(pollInterval).untilAsserted {
        events = (auditService as MockAuditService).getAccountLevelAuditEvents(type, accountId)
        assertThat(events)
          .describedAs("No account-level audit event found for account: $accountId.")
          .isNotEmpty
      }
      return events
    }

    fun <T : AbstractAccountLevelAuditEvent> `then an account-level audit event is produced`(
      waitFor: Duration = DEFAULT_AWAIT_DURATION,
      pollInterval: Duration = DEFAULT_POLL_INTERVAL,
      expected: T,
      accountId: UUID,
      type: NucleusAuditEventType,
    ) {
      val events =
        `then an account-level audit event is produced`(accountId, type, waitFor, pollInterval)
      assertThat(events).contains(expected)
    }

    fun `then an audit event is produced`(
      type: NucleusAuditEventType,
      waitFor: Duration = DEFAULT_AWAIT_DURATION,
      pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    ): List<AbstractAuditEvent> {
      var events = emptyList<AbstractAuditEvent>()
      await.atMost(waitFor).pollInterval(pollInterval).untilAsserted {
        events = (auditService as MockAuditService).getAuditEvents(type)
        assertThat(events).describedAs("No audit event found for type: $type.").isNotEmpty
      }
      return events
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
      assertThat(actualBalance[address]).isEqualTo(balance)
    }
  }
