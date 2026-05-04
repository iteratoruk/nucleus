package iterator.nucleus.accounts

import iterator.nucleus.AbstractJpaEntity
import iterator.nucleus.AbstractJpaRepository
import iterator.nucleus.NucleusHeaders
import iterator.nucleus.Uris
import iterator.nucleus.audit.NucleusAuditEventType
import iterator.nucleus.idempotency.IdempotencyService
import iterator.nucleus.kafka.KafkaConfigurationProperties
import iterator.nucleus.kafka.KafkaConfigurationUtils
import iterator.nucleus.kafka.KafkaConstants
import iterator.nucleus.kafka.OutboundEvent
import iterator.nucleus.kafka.OutboundEventPublisher
import iterator.nucleus.kafka.RegexTopicMessageTypeMapper
import iterator.nucleus.parameters.ClassificationCode
import iterator.nucleus.parameters.LedgerSide
import iterator.nucleus.parameters.ParameterNodeService
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaAdmin.NewTopics
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class AccountStatus {
  OPEN,
  PENDING_CLOSURE,
  CLOSED,
}

@Entity
class Account(
  val accountIdentifier: UUID,
  val stakeholderIdentifier: String,
  val classificationCode: String,
  @Enumerated(EnumType.STRING) val ledgerSide: LedgerSide,
  @Enumerated(EnumType.STRING) val status: AccountStatus,
) : AbstractJpaEntity() {
  companion object {
    fun open(
      classificationCode: String,
      stakeholderIdentifier: String,
    ): Account =
      Account(
        accountIdentifier = UUID.randomUUID(),
        stakeholderIdentifier = stakeholderIdentifier,
        classificationCode = classificationCode,
        ledgerSide = ClassificationCode(classificationCode).ledgerSide,
        status = AccountStatus.OPEN,
      )
  }
}

interface AccountRepository : AbstractJpaRepository<Account> {
  fun findByAccountIdentifier(accountIdentifier: UUID): Account?
}

interface AccountingCodeResolver {
  fun resolve(
    classificationCode: ClassificationCode,
    at: Instant,
  ): String?
}

// Provisional parameter key for the accounting code. The accounting code is destined
// for catalogue declaration (ADR-025); the canonical key will then become
// `{featureName}.{propertyName}` per ADR-008. Until that catalogue work lands, the
// Account context owns the key here, contained to the resolver implementation.
private const val ACCOUNTING_CODE_PARAMETER_KEY = "accounting.code"

@Component
class ParameterHierarchyAccountingCodeResolver(
  val parameterNodeService: ParameterNodeService,
) : AccountingCodeResolver {
  override fun resolve(
    classificationCode: ClassificationCode,
    at: Instant,
  ): String? = parameterNodeService.resolve(classificationCode, at)[ACCOUNTING_CODE_PARAMETER_KEY]
}

data class OpenAccountRequest(
  val classificationCode: String,
  val stakeholderIdentifier: String,
)

data class OpenAccountResponse(
  val accountIdentifier: UUID,
  val status: AccountStatus,
)

object AccountTopics {
  const val ACCOUNT_OPENED = KafkaConstants.PUBLIC_TOPIC_PREFIX + "account.opened"
}

data class AccountOpened(
  val accountIdentifier: UUID,
  val stakeholderIdentifier: String,
  val ledgerSide: LedgerSide,
  val classificationCode: String,
  val accountingCode: String,
  val openingTimestamp: Instant,
  val openedBy: String,
) : OutboundEvent() {
  override val topic = AccountTopics.ACCOUNT_OPENED
  override val key = accountIdentifier.toString()
  override val auditType = NucleusAuditEventType.ACCOUNT_OPENED
  override val auditPrincipal = openedBy
  override val auditTimestamp = openingTimestamp
  override val auditData
    get() =
      mapOf(
        "accountIdentifier" to accountIdentifier.toString(),
        "stakeholderIdentifier" to stakeholderIdentifier,
        "ledgerSide" to ledgerSide.name,
        "classificationCode" to classificationCode,
        "accountingCode" to accountingCode,
        "openingTimestamp" to openingTimestamp.toString(),
      )
}

@Component
class AccountTopicMessageTypeMapper :
  RegexTopicMessageTypeMapper(
    mapOf(AccountTopics.ACCOUNT_OPENED to AccountOpened::class.java),
  )

@Configuration
class AccountKafkaConfiguration {
  @Bean
  fun accountTopics(props: KafkaConfigurationProperties): NewTopics =
    KafkaConfigurationUtils.toNewTopics(
      AccountTopics,
      props.numberOfPartitions,
      props.replicationFactor,
    )
}

@RestController
@RequestMapping("${Uris.API_V1}/accounts")
class AccountsController(
  val service: AccountService,
) {
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  fun open(
    @RequestHeader(NucleusHeaders.IDEMPOTENCY_KEY) idempotencyKey: String,
    @RequestBody request: OpenAccountRequest,
  ): OpenAccountResponse = service.open(idempotencyKey, request)
}

private const val OPEN_ACCOUNT = "OPEN_ACCOUNT"

@Service
@Transactional
class AccountService(
  val accountRepository: AccountRepository,
  val outboundEventPublisher: OutboundEventPublisher,
  val accountingCodeResolver: AccountingCodeResolver,
  val idempotencyService: IdempotencyService,
  val clock: Clock,
) {
  fun open(
    idempotencyKey: String,
    request: OpenAccountRequest,
  ): OpenAccountResponse {
    idempotencyService
      .findExistingResponse(OPEN_ACCOUNT, idempotencyKey, OpenAccountResponse::class)
      ?.let {
        return it
      }

    val openingTimestamp = clock.instant()
    val classificationCode = ClassificationCode(request.classificationCode)
    val accountingCode =
      accountingCodeResolver.resolve(classificationCode, openingTimestamp)
        ?: error("Accounting code unresolved for $classificationCode")
    val account =
      accountRepository.saveAndFlush(
        Account.open(request.classificationCode, request.stakeholderIdentifier),
      )
    outboundEventPublisher.publish(
      AccountOpened(
        accountIdentifier = account.accountIdentifier,
        stakeholderIdentifier = account.stakeholderIdentifier,
        ledgerSide = account.ledgerSide,
        classificationCode = account.classificationCode,
        accountingCode = accountingCode,
        openingTimestamp = openingTimestamp,
        openedBy = account.createdBy!!,
      ),
    )
    val response = OpenAccountResponse(account.accountIdentifier, account.status)
    idempotencyService.record(
      operationId = OPEN_ACCOUNT,
      idempotencyKey = idempotencyKey,
      uri = "${Uris.API_V1}/accounts",
      response = response,
    )
    return response
  }
}
