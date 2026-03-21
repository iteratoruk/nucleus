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