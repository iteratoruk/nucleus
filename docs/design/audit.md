# Technical Design: Audit

## Purpose

This concern governs how a significant business or system happening is recorded as a
first-class, typed event rather than logged ad hoc. It provides a base event type, an
async publication seam, and a swappable sink. The boundary is deliberately narrow: this
document covers the *mechanism* of raising and routing an audit event — the type
hierarchy, the publish path, and the repository sink — not *what* must be audited, how
long records are retained, or what `principal` means. Those are domain and operational
concerns and belong to a forthcoming audit architecture document (see Open Questions).
It also does not cover Kafka messaging, which is a separate concern with a separate
lifecycle (see `docs/design/messaging.md`); the two share a base-type-separation rule but
no longer share a publisher.

## Vocabulary

- **`AbstractAuditEvent`** — the base class every Nucleus audit event extends. It *is a*
  Spring Boot Actuator `AuditApplicationEvent`, constructed from `(timestamp, principal,
  type.name, data)`. Because it is an `AuditApplicationEvent`, publishing it onto the
  Spring application event bus triggers Actuator's built-in audit listener.
- **`NucleusAuditEventType`** — an *interface* (declaring `val name: String`) that is the
  type discriminator every audit event carries. It is deliberately not a single enum: each
  feature package defines its own enum implementing it and so owns its own vocabulary of
  auditable happenings, while the audit package depends on no feature package. An enum
  implementing it satisfies `name` with its constant name, which is the Actuator audit event
  *type* string.
- **`ScheduleAuditEventType`** — the scheduling concern's enum implementing
  `NucleusAuditEventType`, living in the `schedule` package. Its constants
  `SCHEDULED_TASK_STARTED` and `SCHEDULED_TASK_FINISHED` are the audit type strings for
  scheduled-job execution.
- **`GenericAuditEvent`** — a general-purpose concrete event (in the audit package) carrying
  an explicit `type`, optional `principal`, and a free `data` map, for happenings that do not
  warrant their own class.
- **`ScheduledTaskStartedEvent` / `ScheduledTaskFinishedEvent`** — purpose-built concrete
  events for the scheduling concern, defined in the `schedule` package (not audit); each
  fixes its own `type` (a `ScheduleAuditEventType` constant) and builds its own `data` map
  from its constructor arguments.
- **`AuditService`** — a `@Service` with one method, `publishAuditEvent`, annotated
  `@Async`. It publishes the event onto the application event bus and writes nothing
  itself.
- **`AuditEventRepository`** — Actuator's sink interface. Spring Boot's audit listener
  routes each published `AuditApplicationEvent` to the single `AuditEventRepository` bean.
- **`LoggingAuditRepository`** — the current `AuditEventRepository` implementation. Its
  `add` serialises the event to JSON via the shared `ObjectMapper` and logs it at INFO;
  its `find` is unsupported (this is a write-only sink).
- **`@EnableAsync`** — declared on `App`; this is what makes `@Async` on
  `publishAuditEvent` fire on a separate thread rather than inline.
- **`management.endpoints.web.exposure.include: auditevents`** — the Actuator endpoint
  exposure that makes audit machinery live in this app.

## Patterns

### Pattern: Audit event as a first-class typed event

**Problem:** A significant business or system happening must be recorded in a structured,
queryable, type-tagged form — not smeared across free-text log lines that no consumer can
reliably parse or route. The set of auditable happenings must be a closed, reviewable
vocabulary rather than an open field. What *must* be audited is a domain question owned by
the (not-yet-written) audit architecture document; this pattern is the code mechanism that
serves it.

**Approach:** Model every audit event as a data class extending `AbstractAuditEvent`.
`AbstractAuditEvent` extends Actuator's `AuditApplicationEvent`, so a Nucleus audit event
already *is* the framework's audit event — no adapter, no mapping. Each concrete event
supplies a `NucleusAuditEventType` (whose `name` becomes the Actuator type string), an
optional `principal`, and a `data: Map<String, Any>` payload. Two shapes exist and both
are legitimate: `GenericAuditEvent`, where the caller passes the `type` and assembles the
`data` map at the call site; and a purpose-built class such as `ScheduledTaskFinishedEvent`,
which fixes its `type` internally and builds `data` from typed constructor parameters. Reach
for a purpose-built class when the same event is raised from more than one site or when the
`data` map has a fixed, non-trivial shape worth naming; use `GenericAuditEvent` for one-off
or low-structure events.

**Reference implementation:** `iterator.nucleus.audit.AbstractAuditEvent` and
`GenericAuditEvent` in `Audit.kt`; the purpose-built subclass `ScheduledTaskFinishedEvent` in
`iterator.nucleus.schedule.Schedule.kt`. The latter is the model for a purpose-built event,
including how it folds an optional field into the map (`error?.let { ... } ?: emptyMap()`
merged with the always-present entries).

**Rules:**
- A significant business/system happening is *audited* — raised as a typed
  `AbstractAuditEvent` — not logged ad hoc. Logging is for diagnostics; see
  `docs/design/logging.md` for that boundary.
- A new kind of happening requires a new `NucleusAuditEventType` value — a constant on the
  feature package's own enum implementing the interface. That enum is the authoritative
  vocabulary for its package; do not overload an existing type to mean two things.
- The `data` map must be JSON-serialisable by the shared `ObjectMapper` (see
  `docs/design/serialization.md`) — it is serialised whole by the sink. Put primitives,
  strings, enum `.name` values, and plain maps in it, not live domain aggregates or
  lazily-initialised JPA entities.

**Pitfalls:**
- Putting an enum instance directly in `data` and relying on default serialisation instead
  of calling `.name` — `ScheduledTaskFinishedEvent` deliberately stores `status.name`. Match
  that so the serialised form is stable and explicit.
- Reusing `GenericAuditEvent` from several call sites with a hand-copied `data` map. Once a
  second site raises the same event, promote it to a purpose-built class so the map's shape
  is defined once.

### Pattern: Async fire-and-forget publication via the application event bus

**Problem:** Producing an audit event must not couple the producer to the sink, nor slow the
business transaction that raises it. The producer should say "this happened" and move on.

**Approach:** Route every audit event through `AuditService.publishAuditEvent`, which is
`@Async` and does exactly one thing: `publisher.publishEvent(event)` onto Spring's
`ApplicationEventPublisher`. It writes to no repository and knows no sink. The routing to a
sink is the framework's: because `AbstractAuditEvent` is an `AuditApplicationEvent`, Spring
Boot's Actuator audit listener receives it off the bus and hands it to the
`AuditEventRepository` bean. `@Async` (enabled globally by `@EnableAsync` on `App`) makes the
publish fire-and-forget on a separate thread, so a caller inside a request or scheduled job
is not blocked by, and does not fail on, audit handling.

**Reference implementation:** `iterator.nucleus.audit.AuditService.publishAuditEvent` in
`Audit.kt`; `@EnableAsync` on `iterator.nucleus.App`.

**Rules:**
- Publish through `AuditService.publishAuditEvent`. Do not call `ApplicationEventPublisher`
  directly for audit, and do not write to an `AuditEventRepository` directly — the async seam
  and the listener routing are the point.
- Because publication is `@Async` and fire-and-forget, treat audit as best-effort with
  respect to the raising transaction: a failure in the sink does not roll the caller back, and
  the audit event is *not* transactional with the business write. Where a happening must be
  emitted exactly on commit (a Kafka message), that is a different mechanism — see
  `docs/design/messaging.md`.

**Test seam:** Under the `api-test` profile, async runs synchronously and `AuditService` is
replaced by a mock so raised events are assertable (see CLAUDE.md and
`docs/design/serialization.md` test notes); do not assert on log output for audit.

### Pattern: Actuator `AuditEventRepository` as the swappable sink

**Problem:** Where audit events ultimately land — a log, a table, an external system — must be
changeable without touching a single event producer. The producer/sink coupling must be broken
at a stable seam.

**Approach:** Implement Actuator's `AuditEventRepository` as a Spring bean; Spring Boot wires it
as the destination for every published audit event. The current implementation,
`LoggingAuditRepository`, serialises the incoming `AuditEvent` to a JSON string with the shared
`ObjectMapper` and logs it at INFO (`ROOT` is ERROR and `iterator.nucleus` is INFO in
`application.yml`, so these lines are emitted). `find` throws `UnsupportedOperationException`
because this is a write-only sink — there is no query path today. Replacing the sink (to persist
to Postgres, say) is a matter of providing a different `AuditEventRepository` bean; no event
class and no producer changes.

**Reference implementation:** `iterator.nucleus.audit.LoggingAuditRepository` in `Audit.kt`.

**Rules:**
- The sink is the *only* place that knows the destination. Keep serialisation and destination
  logic here, behind `AuditEventRepository`; producers stay ignorant of it.
- Serialise with the shared `ObjectMapper` bean, not a locally constructed one, so audit JSON
  obeys the project's serialization conventions (see `docs/design/serialization.md`).

**Pitfalls:**
- Implementing `find` to satisfy the interface. It is intentionally unsupported; a read path is
  a domain decision (retention, query surface) not yet made.
- The `getLog()` indirection in `LoggingAuditRepository` exists solely so tests can inject a mock
  logger and assert on emitted audit JSON; it is not a general logging pattern to copy.

### Pattern: The feature package owns its own audit event definitions

**Problem:** Every audit event must carry a type discriminator and extend a base event class. If that
discriminator were a single global enum in the audit package, and every purpose-built event class also
lived there, the audit package would have to reach back into each feature's domain — a
`ScheduledTaskFinishedEvent` references the schedule concern's `ScheduledTaskStatus` — producing a
cyclic package dependency between audit and the very modules it is meant to serve. The event
definitions and their vocabulary have to sit on the feature side of that boundary, not the audit side.

**Approach:** The audit package exports only the generic machinery — `AuditService`,
`AbstractAuditEvent`, and the `NucleusAuditEventType` interface — and depends on no feature package.
Each feature package that audits defines *its own* enum implementing `NucleusAuditEventType` and *its
own* `AbstractAuditEvent` subclasses, in its own file. The scheduling concern is the reference: the
`schedule` package declares `ScheduleAuditEventType` (implementing `NucleusAuditEventType`) and the
`ScheduledTaskStartedEvent` / `ScheduledTaskFinishedEvent` classes that extend `AbstractAuditEvent`,
all in `Schedule.kt`. A feature depends on `audit` to obtain the base class, the interface, and
`AuditService`; `audit` depends on nothing feature-specific. The dependency is strictly one-way
(feature → audit), so no cycle can form, and the feature is free to reference its own domain types
(such as `ScheduledTaskStatus`) inside its own event classes.

**Reference implementation:** `ScheduleAuditEventType`, `ScheduledTaskStartedEvent`, and
`ScheduledTaskFinishedEvent` in `iterator.nucleus.schedule.Schedule.kt`, raised from
`QuartzScheduledJob` in the same package; the exported machinery in `iterator.nucleus.audit.Audit.kt`.

**Rules:**
- Define a new audit event, and the enum constant that types it, in the feature package that raises
  it — never in the audit package. Implement `NucleusAuditEventType` for the type and extend
  `AbstractAuditEvent` for the event.
- The audit package must not depend on any peer or domain sub-package — it exports machinery that
  others depend on. This is enforced by the ArchUnit test `audit must not depend on peer feature
  packages` in `PackageDependencyRulesTest`.

**Pitfalls:**
- Reaching for a single global `NucleusAuditEventType` enum, or a central catalogue of every event
  class, in the audit package. That was the earlier shape and it forced audit to depend on feature
  domain types, forming a cycle; the interface-per-feature split exists precisely to prevent it.

## Extension Points

To audit a new happening: in the feature package, add a constant to that package's enum implementing
`NucleusAuditEventType` (or create the enum if the package has none yet); then either raise a
`GenericAuditEvent` with that type and a JSON-serialisable `data` map at the call site, or — if
the event recurs or has a fixed payload shape — add a concrete data class extending
`AbstractAuditEvent` that fixes the type and builds its own `data` map (copy
`ScheduledTaskFinishedEvent`). Publish it through `AuditService.publishAuditEvent`. Nothing in the
audit package changes: the interface, the listener routing, and the sink are already in place.

To change where audit events land, provide an alternative `AuditEventRepository` bean. The
existing `LoggingAuditRepository` is the starter sink, not a fixture.

## Relationships

- **Depends on serialization** (`docs/design/serialization.md`): the sink uses the shared
  `ObjectMapper`, and the `data` map must be serialisable by it.
- **Serves scheduling** (`docs/design/scheduling.md`): `ScheduledTaskStartedEvent` /
  `ScheduledTaskFinishedEvent` are the audit events that scheduled-job execution raises.
- **Sibling to logging** (`docs/design/logging.md`): audit and logging are distinct — a
  significant happening is audited, diagnostics are logged. The rule lives at that boundary.
- **Must stay separate from messaging** (`docs/design/messaging.md`): **Kafka outbound
  payloads (`OutboundEvent`) must NOT extend `AbstractAuditEvent`.** Audit events and Kafka
  messages are different things with different lifecycles — audit is async, best-effort,
  fire-and-forget onto the application event bus; a Kafka message is a plain Jackson POJO sent
  on transaction commit. The base-type separation is the enduring rule; the Kafka publisher no
  longer fans out to an audit event on every message (that fan-out was removed in the messaging
  work), so audit is not driven by Kafka sends at all. The rule is stated here because getting
  it wrong conflates two distinct lifecycles and corrupts the audit vocabulary; the messaging
  side is in `docs/design/messaging.md`.
- **Candidate CLAUDE.md edit:** the conventions section already carries the one-line audit/Kafka
  separation rule; this document is its authoritative expansion.

## ADR References and Candidates

No ADRs are written yet. Candidates embodied in these patterns:

- Building audit on Actuator's `AuditApplicationEvent` / `AuditEventRepository` seam rather than
  a bespoke audit type and store.
- Async, fire-and-forget audit publication (audit is not transactional with the business write).
- Log-as-audit-sink: `LoggingAuditRepository` as the initial `AuditEventRepository`, deferring a
  persistent sink and any read/query path.
- Making `NucleusAuditEventType` an interface and having each feature package own its own audit
  event types and classes, with a strictly one-way feature → audit dependency (enforced by
  ArchUnit), rather than a single global enum and a central catalogue in the audit package.

## Open Questions and Findings

- **No audit architecture document exists.** What *must* be audited, retention, and the meaning
  and provenance of `principal` (it is currently a nullable string that no producer in the
  skeleton populates) are domain and operational questions. They are a forthcoming architecture
  reference and are deliberately not modelled here. Until it exists, `NucleusAuditEventType`
  grows opportunistically per story rather than against a defined catalogue of auditable events.
- **The write-only sink is provisional.** `find` is unsupported and audit output is log lines
  only; there is no persistence and no query surface. This is adequate for the skeleton but is an
  architecture decision awaiting the document above, not a settled end state.
- **The audit↔schedule cycle is resolved** (previously an open finding). `NucleusAuditEventType` is
  now an interface exported by the audit package, and each feature package owns its own enum and event
  classes: `ScheduleAuditEventType`, `ScheduledTaskStartedEvent`, and `ScheduledTaskFinishedEvent`
  live in the `schedule` package, so `ScheduledTaskFinishedEvent` can reference `ScheduledTaskStatus`
  without the audit package ever depending on `schedule`. The dependency is strictly one-way
  (schedule → audit). The ArchUnit test `audit must not depend on peer feature packages` in
  `PackageDependencyRulesTest` now enforces it.