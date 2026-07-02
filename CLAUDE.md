# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What Nucleus is

A banking microservice built with Kotlin 2.0 and Spring Boot 3.5 on Java 21. It provides
parameter-driven account feature configuration, with account management, scheduled financial
processing, and ledger functionality planned. It integrates PostgreSQL (via JPA/Flyway), Redis
(Hibernate second-level cache via Redisson), and Kafka (event-driven workflows), and Quartz
(clustered scheduled jobs).

The `main` branch is currently a **skeleton**: the latest commit reset the domain layer, so only
cross-cutting infrastructure remains in `src/`. The domain (accounts, the parameter value
hierarchy, the feature catalogue) is re-grown story by story under the workflow below. Prior
architecture documents and ADRs were removed in the reset and are reintroduced as their bounded
contexts are re-opened.

## How work happens here — read this before doing anything

This project uses a strict, documentation-driven, role-based process. `docs/human-contributor-guide.md`
is the authoritative description; the essentials:

- **One role per session, never mixed.** The four roles are architect, story-author,
  tdd-implementor, task-implementor, each defined in `docs/roles/`. An architecture question
  surfacing in a TDD session is *parked and deferred to an architecture session*, not resolved
  inline. Role drift degrades output — surface it and stop.
- **Nothing is built without a story, and no story enters TDD with open questions.** Stories,
  spikes, and tasks are GitHub issues, labelled `story`/`spike`/`task`; the issue number is the
  identifier. Load one with `gh issue view <number> --comments`; create one with `gh issue create`
  (templates in `.github/ISSUE_TEMPLATE/`). Architecture domain models live in `docs/architecture/`
  and ADRs in `docs/architecture/adrs/` (`ADR-NNN`) — these stay as files. Personas that define
  stakeholders are in `docs/personas/`.
- **The architecture documents are the authoritative reference for implementation.** Read the
  relevant one before implementing; if it is missing or stale, an architecture session comes first.
- Commit conventions ([Conventional Commits](https://www.conventionalcommits.org/)): stories
  `feat:`/`fix:`, spikes/ADRs `docs:`, tasks `chore:`. Reference the issue with `Closes #N`, and
  optionally the issue number as scope, e.g. `feat(#42): ...`.

TDD is non-negotiable and strictly ordered (failing test → minimum production code → refactor).
Do not write production code before a failing test exists.

## Commands

```bash
./gradlew build            # full test suite + static analysis; produces build/libs/nucleus.jar
./gradlew test             # tests only
./gradlew test --tests "iterator.nucleus.AppApiTests"   # single test class
./gradlew detekt           # lint (config/detekt/detekt.yml; maxIssues: 0 — any finding fails)
./gradlew spotlessCheck    # formatting check
./gradlew spotlessApply    # auto-fix formatting
```

All of `test`, `detekt`, and `spotlessCheck` must pass before a PR. Spotless enforces ktfmt
(2-space indentation) for Kotlin, google-java-format for Java, and formats `src/main/resources/**/*.sql`.

Tests use Testcontainers and require **no locally running services** — Postgres, Redis, and Kafka
containers are started automatically (see `TestContainers`). Running the app locally instead needs
those three services; see `README.md` for the Docker and Minikube/Skaffold recipes. `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`.

## Architecture and conventions

Everything lives under the `iterator.nucleus` package. Domain areas are their own sub-packages
(`kafka`, `idempotency`, `audit`, `schedule`); each Kotlin file typically holds a whole slice
(config, entities, services, DTOs) rather than one-class-per-file.

**Persistence.** JPA entities extend `AbstractJpaEntity` (identity-based equals/hashCode on `id`,
`@Version` optimistic locking, `@CreatedBy`/`@CreatedDate` auditing) or `AbstractMutableJpaEntity`
(adds `lastModified*`). Repositories extend `AbstractJpaRepository<T>`. The audit author is the
`X-Client-ID` request header, propagated by `ClientIdRequestAttributeFilter` +
`ClientIdAuditor`. Schema changes are Flyway migrations in `src/main/resources/db/migration`
(`VNNN__description.sql`); Hibernate never DDLs. Hibernate uses component-path implicit naming and
camel-to-underscore physical naming, with globally-quoted identifiers.

**Serialization.** Use the single `Serialization.mapper` (also the Spring `ObjectMapper` bean).
`BigDecimal` serialises as a JSON *string*, not a number. Monetary/rate values are constrained to
2 or 7 decimal places — use the `toTwoDecimalPlaces`/`toSevenDecimalPlaces` and
`twoDecimalPlaceViolation`/`sevenDecimalPlaceViolation` helpers in `Extensions.kt`.

**Errors.** Throw `NucleusValidationException` (400, carries `NucleusViolation`s) or
`NucleusInternalErrorException` (500, carries a `NucleusErrorCode`). `ErrorHandler`
(`@ControllerAdvice`) maps these to a `NucleusError` body. Add new error kinds to the
`NucleusErrorCode` enum. HTTP constants: `NucleusHeaders`, `Uris.API_V1` (`/api/v1`).

**Audit vs Kafka — keep them separate.** Audit events extend `AbstractAuditEvent` and are
published via `AuditService.publishAuditEvent` (async → `LoggingAuditRepository`). Kafka payloads
are `OutboundEvent` subclasses and **must not** extend `AbstractAuditEvent` — they are plain
Jackson POJOs. `KafkaOutboundEventPublisher.publish` fans out: it emits the audit event *and*
schedules the Kafka send for `AFTER_COMMIT` of the surrounding transaction. Add new event kinds to
the `NucleusAuditEventType` enum. Topics use the `nucleus.private.` / `nucleus.public.` prefixes;
annotate consumers with `@TransactionalRetryingKafkaListener`.

**Idempotency.** Write endpoints take an `Idempotency-Key` header; `IdempotencyService` records
`(operationId, idempotencyKey) → responseBody` and replays the stored response on resubmission.
This is a global, permanent key scope.

**Scheduling.** Implement `ScheduledTask<T>` and register a `ScheduledTaskDetails` bean (helper:
`scheduledTask(...)`). `QuartzScheduledJob` runs them on a clustered, JDBC-backed Quartz store and
emits started/finished audit events; throw `ScheduledTaskException` to control refire.

**Domain modelling rule.** Values resolved from the parameter value hierarchy (accounting codes,
feature configuration, etc.) do **not** become fields on an aggregate — the aggregate stores only
intrinsic, immutable state. Configuration is resolved through the hierarchy, not copied onto the entity.

## Testing

- `AbstractApiTest` — full `@SpringBootTest` + MockMvc, `api-test` profile, `@Sql("/clean.sql")`
  per test. Under this profile async and event multicasting run **synchronously**, and
  `AuditService` is replaced by `MockAuditService` so raised audit events are assertable via
  `mockAuditService.getAuditEvents(type)`. Use the `withHeaders(clientId, idempotencyKey)` DSL.
- `TestOutboundEventCollector.eventsOf(topic, type)` reads published Kafka messages in tests.
- `AbstractJpaRepositoryTest<T, R>` / `AbstractMutableJpaRepositoryTest` give a repository a full
  standard CRUD + auditing test suite; implement `randomValidEntity()` (and `mutateEntity()`).
- ArchUnit is available for architectural constraint tests. Test data helpers are in `TestingFu`.