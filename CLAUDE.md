# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build (also runs tests and produces build/libs/nucleus.jar)
./gradlew build

# Run tests only
./gradlew test

# Run a single test class
./gradlew test --tests "iterator.nucleus.SomeTest"

# Run the application locally (requires services - see below)
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun

# Static analysis and formatting
./gradlew detekt
./gradlew spotlessCheck
./gradlew spotlessApply   # auto-fix formatting
```

Before submitting a PR, ensure `./gradlew test`, `./gradlew detekt`, and `./gradlew spotlessCheck` all pass.

## Required Services

Tests use Testcontainers (no manual setup needed). For local development, start PostgreSQL 17.5, Redis 8.0, and Kafka (confluentinc/cp-kafka:8.10.0) — see README.md for the exact Docker commands.

## Architecture

Nucleus is a Kotlin/Spring Boot 3 banking microservice. All source lives under `src/main/kotlin/iterator/nucleus/`.

### Core framework classes

- **`AbstractJpaEntity`** — base for all immutable JPA entities: auto-generated `Long` id, `@Version` for optimistic locking, JPA auditing (`createdBy` from `X-Client-ID` header, `createdDate`). `AbstractMutableJpaEntity` adds `lastModifiedBy`/`lastModifiedDate`.
- **`AbstractJpaRepository<T>`** — marker interface extending `JpaRepository<T, Long>` used by all repositories.
- **`ErrorHandler`** — `@ControllerAdvice` returning `NucleusError(code, message)` JSON. Add new `NucleusErrorCode` values and handlers here.
- **`Extensions.kt`** — `BigDecimal` helpers: `toTwoDecimalPlaces()` and `toSevenDecimalPlaces()` (HALF_EVEN rounding).

### Kafka (`kafka/Kafka.kt`)

Polymorphic serialization based on topic name. To add a new message type:
1. Implement `TopicMessageTypeMapper` (or subclass `RegexTopicMessageTypeMapper`) and register it as a Spring bean.
2. Use `@TransactionalRetryingKafkaListener(topics = [...])` on listener methods — this composite annotation bundles `@KafkaListener`, `@Transactional`, and `@RetryableTopic` with backoff from `nucleus.defaults.kafka.retry.*` config.
3. Use `KafkaConfigurationUtils.toNewTopics(obj, partitions, replicationFactor)` to auto-declare topics from a `const val` holding object.

### Scheduling (`schedule/Schedule.kt`)

Quartz-backed, persisted in PostgreSQL, cluster-safe. To add a scheduled task:
1. Implement `ScheduledTask<T>` — `run(data: T): ScheduledTaskResult<T>`.
2. Register a `ScheduledTaskDetails<T>` bean using the `scheduledTask(bean, cronExpression, initialJobData)` helper. The job data (type `T`) is serialized to JSON and persisted in Quartz tables; the result's `data` field becomes the input for the next run.
3. `QuartzScheduledJob` dispatches execution and emits `ScheduledTaskStartedEvent`/`ScheduledTaskFinishedEvent` audit events automatically.

### Audit (`audit/Audit.kt`)

`AuditService.publishAuditEvent(event)` is `@Async`. `LoggingAuditRepository` writes events as JSON to SLF4J. Extend `AbstractAuditEvent` and add a value to `NucleusAuditEventType` for new event types.

### Testing

All integration tests extend `AbstractApiTest`, which:
- Starts the full Spring context with the `api-test` profile.
- Uses `@Testcontainers` (via `TestContainers` interface) for PostgreSQL, Redis, and Kafka.
- Replaces `AuditService` with `MockAuditService` (synchronous, clearable) and makes async execution synchronous via `SyncTaskExecutor`.
- Provides `MockMvc mvc` for HTTP assertions.

## Domain Architecture

Design decisions and domain models are recorded in `docs/architecture/`. Read these
before implementing features in the areas they cover — they record decisions that are not
derivable from the code alone and explain why alternatives were rejected.

- `docs/architecture/parameter-value-hierarchy.md` — domain model for the parameter
  configuration bounded context: classification code tree, parameter node aggregates,
  resolution semantics, and account node attachment. The foundational document for
  account opening, account servicing, and the account-features API.
- `docs/architecture/account-features.md` — domain model for the account feature
  catalogue bounded context: the strongly-typed external representation of configurable
  account behaviour, feature definitions (asset interest, liability interest), the
  parameter key mapping convention, the account-features API contract, and resolution
  safety guarantees.
- `docs/architecture/idempotency.md` — domain model for the idempotency cross-cutting
  context: the idempotent operation aggregate, the (operation ID, idempotency key)
  identity model, stored response semantics, the no-op resubmission guarantee, and
  the foundational dependency isolation constraint.
- `docs/architecture/account.md` — domain model for the Account bounded context: the
  Account aggregate, the Account Node Attachment aggregate, the three-state lifecycle
  (`OPEN`, `PENDING_CLOSURE`, `CLOSED`), the opening and closing composites with their
  participant model, the accounting hierarchy distinct from the classification
  hierarchy, function agnosticism (internal/customer accounts share the same shape),
  and the addressability separation from Payments.

The `docs/architecture/adrs/` directory contains Architecture Decision Records:

| ADR | Decision |
|---|---|
| ADR-001 | Parameter nodes hold a full temporal history of values per key across effective dates. |
| ADR-002 | Closed period governance: a period is closed by a `PeriodClosed` event from the Account Servicing context; parameter values with effective dates in closed periods are rejected at write time. |
| ADR-003 | Account-level parameter values are preserved unchanged on node transfer. |
| ADR-004 | Scheduled processing resolves parameter values against the business date being processed, never the wall-clock time at execution. |
| ADR-005 | The feature catalogue is unified; features differing by ledger side are distinct named entries; the ledger-side prefix of the classification code enforces applicability at submission time. |
| ADR-006 | Explicit absence is represented as a sentinel parameter value that terminates the resolution walk, distinguishing deliberate inheritance suppression from non-configuration. |
| ADR-007 | `GET /account-features/{classificationCode}?asAt={date}` returns resolved classification-node configuration for a hypothetical account without requiring an account to exist. |
| ADR-008 | Parameter keys follow `{featureName}.{propertyName}` — the feature name is the namespace, making cross-feature mis-resolution structurally impossible independent of API-layer validation. |
| ADR-009 | The account-features API uses a single submission-level effective date applying to all properties; per-property effective dates are not supported in the initial implementation. |
| ADR-011 | The ledger side is a closed two-value enumeration: `ASST` (asset) and `LIAB` (liability). Not extensible without a Nucleus deployment. Supersedes provisional examples (`LEND`, `SAVE`, `MORT`) in earlier documents. |
| ADR-012 | Package structure follows bounded context boundaries; flat compound names (`accountfeatures`, not `account.features`); acyclic dependency graph with `parameters` as the foundational package. |
| ADR-013 | Exception types used to produce HTTP error responses are defined in the root `iterator.nucleus` package. Sub-packages throw root-defined exceptions. `ErrorHandler` is the single `@ControllerAdvice`. `NucleusValidationException` is the standard mechanism for structured validation failures. |
| ADR-014 | Idempotency keys are scoped to (operation ID, idempotency key) only — not to any resource dimension (classification code, account, etc.) — and do not expire. A recognised key returns the original stored response unconditionally. |
| ADR-015 | Idempotent response bodies are serialised to JSON text for persistence and deserialised on retrieval. Breaking changes to serialised response types require a migration strategy before deployment. |
| ADR-016 | The `idempotency` package is a foundational cross-cutting context: it depends on nothing within the Nucleus bounded context graph, enforced by `BoundedContextDependencyTest`. All bounded contexts may consume it freely; it may never depend on any of them without superseding this ADR. |
| ADR-017 | Three openness categories: `GLOBAL` (no constraint, permissive default), named processing boundary (e.g. `BUSINESS_DAY_CLOSE` — backdating within open window only), and `PROSPECTIVE_ONLY`. `PeriodClosed`/`PeriodReopened` from ADR-002 are the lifecycle events for `BUSINESS_DAY_CLOSE`. |
| ADR-018 | `PROSPECTIVE_ONLY` openness category: effective datetime must be strictly after wall-clock time at write; applies to properties whose past-effective change would corrupt derived internal properties of already-open accounts. |
| ADR-019 | Derived internal properties: values Nucleus calculates from feature properties at account opening and stores immutably in the Account context. The maturity date (from `fixedTerm.termPeriod`) is the first instance. Contributing properties must be `PROSPECTIVE_ONLY`. |
| ADR-020 | Per-property openness validation: each property validated against its own openness category independently; any violation causes total submission rejection with per-property error attribution. |
| ADR-021 | JSR 303 / Hibernate Validator as the primary validation mechanism: constraint declarations co-located with request body types via standard annotations; custom `ConstraintValidator` for domain constraints requiring bean injection; `ErrorHandler` handles `MethodArgumentNotValidException`; `NucleusValidationException` retained for constraints requiring request context unavailable to body validators (e.g. ledger-side applicability). Adoption deferred until the account features and servicing work begins. |
| ADR-022 | Account identity and stakeholder reference: account identifier is a Nucleus-generated UUID; stakeholder identifier is an opaque, mutable value reference, not an aggregate; the identifier may be updated on `OPEN` accounts and emits `AccountStakeholderChanged`; stakeholder-level views (set membership, financial aggregates) are derived from the account population with continuous materialisation via the internal accounting feature anticipated as the default direction on performance, audit, and reconciliation grounds. |
| ADR-023 | Account status lifecycle: closed three-state enumeration (`OPEN`, `PENDING_CLOSURE`, `CLOSED`); linear forward-only transitions; `CLOSED` is structurally terminal; status governs which operations (writes, transfers, configuration changes) are permitted; the `PENDING_CLOSURE` → `CLOSED` transition is gated by pre-close participants (per ADR-031) whose synchronous predicate assertions must all be positive, with Ledger's zero-balance assertion the canonical instance. |
| ADR-025 | Accounting code as a feature property in the Account Feature Catalogue; resolved hierarchically via the parameter value hierarchy; ledger-side consistency invariant; `GLOBAL` openness with the structural-immutability constraint of ADR-026 applying orthogonally. |
| ADR-026 | Accounting code immutability under non-`CLOSED` accounts: a write to an existing accounting code parameter value at a node is rejected if any non-`CLOSED` account is attached at the node or any descendant; structurally distinct from openness; out-of-band migration is the only mechanism for change under active accounts. |
| ADR-027 | Account Node Attachment aggregate is reassigned to the Account bounded context, superseding Parameter Value Hierarchy OQ-5; rationale grounded in aggregate identity (the account), invariants (account-state-driven), and lifecycle co-location. |
| ADR-028 | Autonomous closure mechanism: the `PENDING_CLOSURE` → `CLOSED` transition is triggered by a per-account precondition projection updated by events from contributing contexts; when async preconditions are satisfied, the close runs synchronously through the pre-close participant phase (per ADR-031) and commits only on positive assertion from every pre-close participant; idempotent and monotonic projection semantics. |
| ADR-029 | Account opening configuration completeness as a facet of catalogue validity: completeness is determined by the Account Feature Catalogue against the resolved map; requires catalogue extensions to express conditional inter-property requirements and per-property default values. |
| ADR-030 | Account function agnosticism: the Account aggregate does not distinguish internal from customer accounts; every account has the same structural shape, operations, and lifecycle; ledger entries against any account are uniformly queryable with materialisation strongly recommended; addressability (BBAN, IBAN) is a Payments context concern, not an Account aggregate attribute. |
| ADR-031 | Lifecycle participant model: opening, prepare-to-close, and pre-close participant phases; opening and prepare-to-close participants contribute work, pre-close participants assert predicates as a synchronous final check before close commits; Ledger's pre-close zero-balance assertion is the canonical pre-close predicate (substantive predicate owned by Ledger, orchestration owned by Account); contributing contexts include Account Feature Catalogue, Account Servicing, Payments, Ledger; zero-participant case valid; implementation may defer general infrastructure but must not foreclose its introduction. |

Persona and role documents in `docs/personas/` and `docs/roles/` define the actors in
the system and the modes in which Claude Code operates in this repository.

## Conventions

- Commit messages must follow [Conventional Commits](https://www.conventionalcommits.org/) (e.g. `feat:`, `fix:`, `chore:`).
- REST API paths are prefixed `/api/v1`.
- The `X-Client-ID` request header is used as the JPA auditor (`createdBy`/`lastModifiedBy`).
- Scheduled task cron expressions use UTC.

### SQL and JPA mapping

**Flyway migrations** use lowercase keywords and quoted identifiers throughout (e.g.
`create table "parameter_node"`, not `CREATE TABLE parameter_node`). This is consistent with
`globally_quoted_identifiers: true` in `application.yml`, which causes Hibernate to quote all
identifiers in generated SQL.

**JPA entity annotations** — `@Table`, `@Column`, and `@JoinColumn` are unnecessary in the
normal case and should be omitted. Hibernate derives physical names from the
`CamelCaseToUnderscoresNamingStrategy` physical naming strategy and the
`ImplicitNamingStrategyComponentPathImpl` implicit naming strategy configured in
`application.yml`. Kotlin nullability (`val` vs `val?`) conveys nullability at the code level;
`NOT NULL` constraints are enforced at the schema level in Flyway migrations.

The naming derivations to rely on:
- Entity class `FooBar` → table `foo_bar`
- Property `someField` → column `some_field`
- `@ManyToOne val relatedEntity: RelatedEntity` → FK column `related_entity_id` (field name
  snake-cased, suffixed with `_id`). Name the field after the related entity type (e.g.
  `parameterNode`, not `node`) so the derived FK column name is unambiguous and descriptive.