# Technical Design: Audit

## Purpose

This concern governs how a significant business or system happening is recorded as a
first-class, typed event rather than logged ad hoc. It provides a base event type, an
async publication seam, and a swappable sink. The boundary is deliberately narrow: this
document covers the *mechanism* of raising and routing an audit event — the type
hierarchy, the publish path, and the repository sink — not *what* must be audited, how
long records are retained, or what `principal` means. Those are domain and operational
concerns and belong to a forthcoming audit architecture document (see Open Questions).
It also does not cover Kafka messaging, which shares a fan-out publisher but is a
separate concern with a separate lifecycle (see `docs/design/messaging.md`).

## Vocabulary

- **`AbstractAuditEvent`** — the base class every Nucleus audit event extends. It *is a*
  Spring Boot Actuator `AuditApplicationEvent`, constructed from `(timestamp, principal,
  type.name, data)`. Because it is an `AuditApplicationEvent`, publishing it onto the
  Spring application event bus triggers Actuator's built-in audit listener.
- **`NucleusAuditEventType`** — a closed enum enumerating the vocabulary of auditable
  happenings. Today: `SCHEDULED_TASK_STARTED`, `SCHEDULED_TASK_FINISHED`. Its `name` is
  the Actuator audit event *type* string.
- **`GenericAuditEvent`** — a general-purpose concrete event carrying an explicit
  `type`, optional `principal`, and a free `data` map, for happenings that do not warrant
  their own class.
- **`ScheduledTaskStartedEvent` / `ScheduledTaskFinishedEvent`** — purpose-built concrete
  events for the scheduling concern; each fixes its own `type` and builds its own `data`
  map from its constructor arguments.
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

**Reference implementation:** `iterator.nucleus.audit.AbstractAuditEvent` and its subclasses
`GenericAuditEvent` and `ScheduledTaskFinishedEvent` in `Audit.kt`. The latter is the model
for a purpose-built event, including how it folds an optional field into the map
(`error?.let { ... } ?: emptyMap()` merged with the always-present entries).

**Rules:**
- A significant business/system happening is *audited* — raised as a typed
  `AbstractAuditEvent` — not logged ad hoc. Logging is for diagnostics; see
  `docs/design/logging.md` for that boundary.
- A new kind of happening requires a new `NucleusAuditEventType` value. The enum is the
  authoritative vocabulary; do not overload an existing type to mean two things.
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

### Pattern: The audit package owns every audit event definition

**Problem:** Every audit event must carry a `NucleusAuditEventType`, and both that enum and
`AbstractAuditEvent` live in the audit package. If a publishing module (say `schedule`) defined its
own audit event subclasses, that module would depend on the audit package for the base type and the
enum — and the audit machinery, which must know the concrete event, would tend to reach back for the
module's types, producing a cyclic package dependency. The event definitions have to sit on one side
of that boundary.

**Approach:** Define all audit event classes in the audit package (`Audit.kt`), even for happenings
raised by other modules. `ScheduledTaskStartedEvent`/`ScheduledTaskFinishedEvent` live in `audit`, not
in `schedule`, although `schedule` is what raises them. A publishing module depends on the audit
package to obtain the event class and `AuditService`, and raises it; it does not define events. This
keeps every event definition in one catalogue and the dependency direction one-way (publishers →
audit).

**Reference implementation:** `ScheduledTaskStartedEvent` / `ScheduledTaskFinishedEvent` in
`iterator.nucleus.audit.Audit.kt`, raised from `QuartzScheduledJob` in the `schedule` package.

**Rules:**
- Define a new audit event in the audit package, never in the module that raises it. The raising
  module imports it.
- The audit package must not depend on any peer or domain sub-package — it is a catalogue that others
  depend on. Enforce with an ArchUnit test (does not yet exist, and would currently fail — see
  Findings).

**Pitfalls:**
- Defining an audit event beside the code that raises it feels natural but inverts the dependency and
  forms a cycle the moment the event references audit's `NucleusAuditEventType` (which it must).

## Extension Points

To audit a new happening: add a value to `NucleusAuditEventType`; then either raise a
`GenericAuditEvent` with that type and a JSON-serialisable `data` map at the call site, or — if
the event recurs or has a fixed payload shape — add a concrete data class extending
`AbstractAuditEvent` that fixes the type and builds its own `data` map (copy
`ScheduledTaskFinishedEvent`). Publish it through `AuditService.publishAuditEvent`. Nothing else
changes: the listener routing and the sink are already in place.

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
  on transaction commit. The shared `KafkaOutboundEventPublisher` fans out to *both* an audit
  event and a Kafka send, which is precisely why the payload types must not be conflated. The
  full treatment of that fan-out is in `docs/design/messaging.md`; the rule is stated here
  because getting it wrong corrupts the audit vocabulary.
- **Candidate CLAUDE.md edit:** the conventions section already carries the one-line audit/Kafka
  separation rule; this document is its authoritative expansion.

## ADR References and Candidates

No ADRs are written yet. Candidates embodied in these patterns:

- Building audit on Actuator's `AuditApplicationEvent` / `AuditEventRepository` seam rather than
  a bespoke audit type and store.
- Async, fire-and-forget audit publication (audit is not transactional with the business write).
- Log-as-audit-sink: `LoggingAuditRepository` as the initial `AuditEventRepository`, deferring a
  persistent sink and any read/query path.
- The audit package as the single owner of all audit event definitions, with a one-way publisher →
  audit dependency, rather than events defined beside their producers.

## Open Questions and Findings

- **No audit architecture document exists.** What *must* be audited, retention, and the meaning
  and provenance of `principal` (it is currently a nullable string that no producer in the
  skeleton populates) are domain and operational questions. They are a forthcoming architecture
  reference and are deliberately not modelled here. Until it exists, `NucleusAuditEventType`
  grows opportunistically per story rather than against a defined catalogue of auditable events.
- **The write-only sink is provisional.** `find` is unsupported and audit output is log lines
  only; there is no persistence and no query surface. This is adequate for the skeleton but is an
  architecture decision awaiting the document above, not a settled end state.
- **The audit package currently violates its own no-peer-dependency rule.** `ScheduledTaskFinishedEvent`
  references `ScheduledTaskStatus` from the `schedule` package (`Audit.kt` imports
  `iterator.nucleus.schedule.ScheduledTaskStatus`), while `schedule` depends on `audit` to raise its
  events — a genuine bidirectional package cycle. Two ways out, not yet chosen, presented with their
  trade-offs rather than canonised:
  1. **Genericise the audit seam** so publishers need not depend on the audit package at all: a module
     publishes a plain Spring application event (or a `GenericAuditEvent` assembled from primitives and
     strings), and the audit module observes and processes it. Event-*shape* knowledge leaves the
     publishers; the cycle cannot form because a publisher depends only on Spring's
     `ApplicationEventPublisher`. Trade-off: gives up the typed, purpose-built event classes and their
     compile-time shape, and moves the enum/type coupling to the audit module's own listener.
  2. **Make audit a fully self-contained catalogue** of every publishable event, and break the peer
     dependency by not referencing `ScheduledTaskStatus` at all — store the status as its `.name`
     string, or relocate the status type to the parent (or audit) package. Trade-off: the audit
     catalogue must then carry knowledge of every domain's events, increasing coupling in the other
     direction (audit needs to know about domains), though without a compile-time dependency on their
     packages.
  The choice is an architecture decision. An ArchUnit test forbidding `audit` → peer-package
  dependencies should be added once it is made; today that test would fail on the existing
  `ScheduledTaskStatus` reference. Recorded here, not resolved. Scheduling is the other half of the
  cycle — see the corresponding finding in `docs/design/scheduling.md`.