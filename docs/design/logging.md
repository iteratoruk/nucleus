# Technical Design: Logging

## Purpose

This document governs two small things: how a Nucleus class acquires an SLF4J `Logger`, and the
standing rule about when a developer is allowed to write a log line at all. The second is the more
important. Logging in Nucleus is deliberately rare — a significant business or system event is an
*audit event*, not a log line — so most of this document is about the log lines you should *not*
write. The mechanics (a logger-acquisition delegate and a JSON logback configuration) exist; the
discipline around them is what keeps the log stream diagnostic rather than a second, untyped event
store. This document does not cover audit events themselves; those are in `docs/design/audit.md`.

## Vocabulary

`LoggerDelegate<R>` (`iterator.nucleus.LoggerDelegate`) is a `ReadOnlyProperty<R, Logger>` that
resolves an SLF4J `Logger` named for the *enclosing* class of the property's owner. Its private
`unwrapCompanionObject` helper detects when the owning `Class` is a Kotlin companion and returns the
outer class instead, so the logger is named for the domain class rather than its synthetic
`$Companion`. The logging backend is Logback, configured by `src/main/resources/logback.xml`; levels
are set in the `logging.level` block of `src/main/resources/application.yml`. Log output is
structured JSON via Logstash's `LogstashEncoder`. Where "significant event" appears below, the
carrier is an `AbstractAuditEvent` published through `AuditService` — see `docs/design/audit.md`, not
this document.

## Patterns

### Pattern: Logger acquisition via companion-object delegate

**Problem:** A class that needs a logger must obtain one whose name is stable and correct — named for
the class itself, not for the anonymous or synthetic type that happens to hold the reference. Kotlin's
idiom for a class-level (rather than per-instance) logger is a property on the `companion object`, but
`this::class` inside a companion is the companion, whose runtime name is `Outer$Companion`. A logger
named after the companion is subtly wrong: it breaks per-class level configuration and log filtering.

**Approach:** Declare the logger as a delegated property inside the class's `companion object`:

```kotlin
companion object {
  private val LOG: Logger by LoggerDelegate()
}
```

`LoggerDelegate.getValue` calls `LoggerFactory.getLogger(unwrapCompanionObject(thisRef.javaClass))`.
Because the property lives on the companion, `thisRef` is the companion instance;
`unwrapCompanionObject` sees that `thisRef.javaClass` is the registered companion of its enclosing
class and returns the enclosing class, so the resulting logger is named for the outer class. Reference
it through `LOG` at the call site. Acquire every logger this way.

**Reference implementation:** `iterator.nucleus.audit.Audit.kt` — `LoggingAuditRepository` declares
`private val LOG: Logger by LoggerDelegate()` in its companion object. (It is cited here only as the
canonical acquisition example; what it logs is discussed under the standing rule below, and audit
itself is documented in `docs/design/audit.md`.)

**Rules:**
- Declare the delegate inside a `companion object` and keep it `private`.
- Name the property `LOG`, matching the reference implementation, so the pattern is greppable.
- Never construct a logger directly with `LoggerFactory.getLogger(...)` and never derive its name from
  the companion.

**Pitfalls:**
- Naming a logger after the companion object — e.g. `LoggerFactory.getLogger(this::class.java)` inside
  the companion — yields a logger named `...$Companion`. `LoggerDelegate` exists precisely to prevent
  this; bypassing it reintroduces the bug.
- Declaring the delegate outside a companion object (as an instance property) creates a new logger per
  instance and defeats the class-level intent. Keep it in the companion.

### Pattern: Baseline levels and what actually surfaces

**Problem:** A developer needs to know whether a log line they are considering will ever be seen, and
at what cost, before deciding to write it.

**Approach:** Logback (`logback.xml`) defines a single appender, `JSON`, a `ConsoleAppender` whose
encoder is `net.logstash.logback.encoder.LogstashEncoder` with `includeCallerData` set to `true` — so
every emitted event is a structured JSON record carrying caller location. The root logger's level is
`${LOGGING_LEVEL_ROOT:-ERROR}`, i.e. `ERROR` unless overridden by environment. Spring Boot's
`logging.level` block in `application.yml` then sets `ROOT: ERROR` and `iterator.nucleus: INFO`. The
effective baseline is therefore: **first-party Nucleus code logs at `INFO` and above; everything else
— framework, libraries — is silent below `ERROR`.** `includeCallerData: true` makes each record more
expensive to produce (it walks the stack for the call site), which is another reason the stream should
stay sparse.

**Reference implementation:** `src/main/resources/logback.xml` and the `logging.level` block of
`src/main/resources/application.yml`.

**Rules:**
- Do not add appenders or file logging in code or per-service config; the JSON console appender is the
  contract, and structured JSON on stdout is what the platform collects.
- Set levels through `logging.level` in `application.yml`, not by editing `logback.xml`.
- Treat `INFO` on `iterator.nucleus` as the floor for anything you intend to be seen in normal
  operation; a `DEBUG` line in first-party code will not surface under the baseline and is dead weight
  unless a level override is deliberately configured.

## The standing rule: audit it, don't log it

Logging in Nucleus is **rare**. This is prescriptive, not a preference. A significant business or
system event — something a human operator, auditor, or another service would care that it happened —
is *audited*: it is a typed `AbstractAuditEvent` published via `AuditService`
(`docs/design/audit.md`), never a log line. Logging is not a debugger and not an event store.

Before writing any log statement, ask one question: **is the thing significant?** If yes, it is an
audit event, and the correct action is to define or raise the appropriate `AbstractAuditEvent`, not to
call `LOG.info(...)`. Logs are reserved for genuinely operational or diagnostic concerns that are
*not* domain events — the kind of low-level, infrastructural signal that helps diagnose the process
itself and would never appear in an audit trail. When in doubt, the default is audit, not log.

Concretely, under a red test:
- An account was opened, a parameter value resolved, a scheduled task started or finished, a payment
  processed, a validation rejected a request — **significant. Audit it.** These belong in the
  `NucleusAuditEventType` catalogue and flow through `AuditService`. Do not also log them.
- A connection pool exhausted, a retry backoff fired, an unexpected-but-recoverable infrastructural
  condition worth a breadcrumb for an operator — **operational. A log line may be justified**, at
  `INFO` or above so it surfaces.
- A value you want to inspect while getting a test green — **neither.** That is debugging; delete it
  before the refactor step. It is not a log and not an audit event.

There is one live first-party `INFO` log in the skeleton, and it is instructive precisely because it
looks like an exception to the rule and is not. `LoggingAuditRepository.add` in `audit/Audit.kt`
serialises each audit event to JSON and writes it at `INFO`. This is not "logging a significant
event" in the sense the rule forbids: here **logging IS the audit sink**. The Actuator
`AuditEventRepository` seam is wired, for now, to emit audit events onto the JSON log stream, so the
one legitimate stream of `INFO` records is the audit trail itself, arriving through the logging
backend rather than around it. The coupling is temporary and is owned by `docs/design/audit.md`; do
not read it as licence to log business events directly. If your event is significant, it goes through
`AuditService` — and if the sink still happens to be the log, that is audit's decision to make, not
yours at the call site.

## Extension Points

A new class that needs a logger declares `companion object { private val LOG: Logger by
LoggerDelegate() }` and references `LOG` — no registration, no configuration. Adjusting visibility of a
package's logging is a level entry under `logging.level` in `application.yml` (e.g. narrowing a noisy
dependency to `WARN`), never a code change. The far more common "extension" is the one you *don't*
make: the event you were about to log is significant, so you extend the audit catalogue
(`NucleusAuditEventType`) instead — see `docs/design/audit.md`.

## Relationships

This concern is subordinate to **audit** (`docs/design/audit.md`): audit is the preferred and default
channel for every significant event, and the current audit sink (`LoggingAuditRepository`) happens to
emit through this logging backend. Any story tempted to add logging around a domain event should be
redirected to the audit pattern. `LoggerDelegate` is otherwise infrastructural and depended upon by no
other design concern.

Candidate CLAUDE.md convention edit: if the conventions section does not already headline it, add a
one-line rule — **"Logging is rare: a significant event is an audit event, not a log; acquire loggers
via `companion object { private val LOG: Logger by LoggerDelegate() }`."** The authoritative statement
is this document; CLAUDE.md carries only the headline.

## ADR References and Candidates

- **Candidate ADR — structured JSON logging to stdout as the sole appender.** `logback.xml` forecloses
  file appenders and plain-text output in favour of a single Logstash-encoded console appender, a
  decision that binds log collection to the platform's stdout pipeline. Worth an ADR when the log/audit
  boundary is formalised.
- **Candidate ADR — audit-over-logging as the channel for significant events.** The rule that
  significant events are audited rather than logged is an architectural decision with teeth; it most
  naturally lives with the audit context's ADRs (`docs/design/audit.md`), cross-referenced here.

## Open Questions and Findings

- The audit sink is currently the log stream (`LoggingAuditRepository` writes audit events at `INFO`),
  which couples the audit trail to the logging backend and means the one legitimate `INFO` stream is
  audit, not diagnostics. This is a known, temporary arrangement owned by audit; flagged here so the
  coupling is not mistaken for a general licence to log business events. Escalate to audit, not
  resolved here.
- `includeCallerData: true` is enabled globally, which is comparatively expensive per record. It is
  cheap only because the stream is meant to be sparse; if logging volume ever grows, this setting
  should be revisited. Noted, not a blocker.