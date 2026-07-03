# Technical Design: Error Handling

## Purpose

This concern governs how a failure inside the service becomes an HTTP error response with a
stable, machine-readable shape. The seam is a single Spring `@ControllerAdvice` (`ErrorHandler`)
that translates typed Nucleus exceptions into a `NucleusError` body carrying a `NucleusErrorCode`
and a status set by `@ResponseStatus`. The boundary of this document is deliberately narrow: it
covers only what exists in the skeleton, which is one exception type mapped to one status
(`NucleusInternalErrorException` → 500). It does not cover client-error (4xx) handling or
validation, because — as recorded under Findings — none of that machinery currently exists in
`src/`; CLAUDE.md's "Errors" paragraph now records that 4xx/validation is deliberately left open,
so document and code agree. Where a failure is domain- or concern-specific (idempotency,
persistence), the *decision to throw* belongs to that concern; the *translation to a response*
belongs here. This narrowness is about the *HTTP error-mapping* seam specifically: a control-flow
exception that never reaches a controller (see the catalogue pattern below) is outside its scope.

## Vocabulary

- **`NucleusInternalErrorException`** (`ErrorHandler.kt`) — a `RuntimeException` subclass carrying a
  `NucleusErrorCode`, a human-readable `message`, and an optional `cause`. Thrown at a failure site
  to signal an unrecoverable internal (server-side) error.
- **`NucleusErrorCode`** (`ErrorHandler.kt`) — the closed enum of machine-readable error codes. It
  currently holds a single value, `IDEMPOTENT_OPERATION_RESPONSE_UNREADABLE`.
- **`NucleusError`** (`ErrorHandler.kt`) — the response DTO, a data class of `{ code, message }`
  serialised to the client as the error body.
- **`ErrorHandler`** (`ErrorHandler.kt`) — the `@ControllerAdvice` bean holding the
  `@ExceptionHandler` methods. This is the single error-mapping seam for the service.

These are pure infrastructure terms; none of them maps to a domain concept, so there is no
architecture document to reference for them. The *choice* of when a given concern should throw an
internal error is owned by that concern's design document, not here.

## Patterns

### Pattern: Typed exception mapped to a response by the ControllerAdvice

**Problem:** Code deep inside a service or repository detects an unrecoverable failure and must turn
it into a well-formed HTTP error without the failure site knowing anything about HTTP, status
codes, or response serialisation — and without every controller re-implementing error assembly.

**Approach:** The failure site throws a typed Nucleus exception carrying a `NucleusErrorCode` and a
message. It never touches HTTP. The `ErrorHandler` `@ControllerAdvice` owns one `@ExceptionHandler`
method per exception type; the method is annotated with `@ResponseStatus` to fix the HTTP status and
`@ResponseBody` to serialise its return, and it constructs a `NucleusError` from the exception's
`code` and `message`. For `NucleusInternalErrorException` the mapping is
`@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)` (500) and the body is
`NucleusError(code = e.code, message = e.message)`. Adding a new mapped exception type means adding a
sibling handler method with its own `@ResponseStatus`; the exception-to-status relationship lives
entirely in the advice.

**Reference implementation:** `ErrorHandler.handleInternalError` in
`src/main/kotlin/iterator/nucleus/ErrorHandler.kt`. The canonical throw site is
`IdempotencyService.findExistingResponse` in
`src/main/kotlin/iterator/nucleus/idempotency/Idempotency.kt`, which catches a `JacksonException`
and rethrows it as `NucleusInternalErrorException(code = IDEMPOTENT_OPERATION_RESPONSE_UNREADABLE,
…, cause = e)`.

**Rules:**
- Signal a failure by throwing the typed Nucleus exception at the site that detects it. Do not
  hand-build a `ResponseEntity`, set a status, or assemble a `NucleusError` in a controller or
  service — response translation is the advice's job alone.
- Every mapped exception carries a `NucleusErrorCode`; the code, not the message, is the client's
  machine-readable contract. The `message` is for humans and may change.
- Preserve the originating throwable as `cause` when wrapping a caught exception, so the stack trace
  survives into logs.
- The status is declared by `@ResponseStatus` on the handler method, one status per exception type.
  Keep the exception's semantics and its status aligned: `NucleusInternalErrorException` means
  server fault and maps to 500.

**Pitfalls:**
- Catching an exception low down and returning a sentinel or `null` instead of throwing, so the
  advice never fires and the caller silently proceeds on bad state. The idempotency reference throws
  rather than returning `null` precisely because an unreadable stored response is a fault, not an
  absence.
- Throwing a bare `RuntimeException` (or a Spring/Jackson exception) expecting the advice to map it.
  Only the registered `@ExceptionHandler` types are translated; anything else falls through to
  Spring's default error handling and escapes the `NucleusError` contract.
- Reusing `NucleusInternalErrorException` for a condition that is actually the client's fault. A 500
  tells the caller "retry, it's us"; using it for bad input misleads the caller and hides the defect.
  Such a condition needs a client-error path, which does not yet exist (see Findings).

### Pattern: Closed error-code enum as the machine-readable vocabulary

**Problem:** Clients need to branch on *what* went wrong without parsing prose. Free-text messages
are unstable and unmatchable; an open string code invites divergent, undocumented values.

**Approach:** `NucleusErrorCode` is a Kotlin `enum` — a closed set. Every mapped exception carries
one of its values, and it is serialised verbatim into the `NucleusError.code` field. The enum *is*
the exhaustive published vocabulary of failures the client may encounter; there is no other channel
for a code.

**Reference implementation:** the `NucleusErrorCode` enum in
`src/main/kotlin/iterator/nucleus/ErrorHandler.kt`, and its single value's use at the idempotency
throw site cited above.

**Rules:**
- A new distinct failure condition gets a new enum value. Do not overload an existing code for a
  different meaning, and do not smuggle discriminating detail into the free-text `message` in place
  of a code.
- Enum value names are `SCREAMING_SNAKE_CASE` and name the condition, not the remedy
  (`IDEMPOTENT_OPERATION_RESPONSE_UNREADABLE` names what is wrong).
- Treat the set of values as a client-facing contract: additive changes are safe, renames and
  removals are breaking.

**Pitfalls:**
- Introducing a parallel error-code mechanism (a `String` constant, an HTTP-only signal) for a new
  concern instead of extending the one enum. The value of a closed vocabulary is entirely in its
  being the only one.

### Pattern: The error-handling package is the central exception catalogue

**Problem:** If each sub-package defines its own exception types and its own advice, error-to-HTTP
mapping fragments, two packages can invent overlapping codes, and the set of things a client can be
told went wrong stops being knowable in one place. The failure vocabulary must have a single owner,
and that owner must sit where every package can depend on it without creating a cycle.

**Approach:** All HTTP-mapped exception types — those that travel through a controller and become a
`NucleusError` via the advice — together with the `NucleusError` DTO and the `NucleusErrorCode` enum
live in the top-level `iterator.nucleus` package (in `ErrorHandler.kt`), alongside the single
`@ControllerAdvice`. Sub-packages (`idempotency`, `kafka`, `schedule`, …) *reference* these
top-level types — they throw `NucleusInternalErrorException` with a `NucleusErrorCode` — but never
define their own HTTP-mapped exception type or a second advice. `IdempotencyService` is the model: it
imports `NucleusInternalErrorException`/`NucleusErrorCode` from the parent package and throws them; it
defines no mapped exception of its own. Because the error-handling types live in the top-level package
and depend on nothing domain-specific, every package may throw them and none creates a dependency
cycle.

The isolation is not a blanket "all exceptions live in the top-level package" rule, and stating it
that way would be wrong. It applies only to *HTTP-mapped* exceptions. A purely internal control-flow
exception that never reaches a controller or the advice legitimately lives in its own feature package:
`ScheduledTaskException` (`schedule/Schedule.kt`) is the standing example — a `ScheduledTask` throws it
to signal failure and control whether Quartz refires, and `QuartzScheduledJob` catches it in the same
package, converting it to a Quartz `JobExecutionException`. It has no `NucleusErrorCode`, is never
serialised into a `NucleusError`, and correctly stays out of the top-level catalogue. What guarantees
the catalogue's single ownership is therefore not a rule about where exceptions may be declared, but
two structural facts: the `@ControllerAdvice` is the sole error-mapping seam, and the error-handling
types are a dependency leaf.

**Reference implementation:** `NucleusInternalErrorException`, `NucleusError`, `NucleusErrorCode`, and
`ErrorHandler`, all in `src/main/kotlin/iterator/nucleus/ErrorHandler.kt` (the root package); the
consuming throw site in `idempotency/Idempotency.kt`. The counter-example — an internal exception that
correctly resides in a sub-package — is `ScheduledTaskException` in `schedule/Schedule.kt`.

**Rules:**
- Define HTTP-mapped exception types and error codes only in the top-level package. A sub-package
  throws the shared mapped types; it must not declare its own mapped exception class or a second
  `@ControllerAdvice`. Internal control-flow exceptions that never reach the advice are exempt and
  belong with the concern that throws and catches them.
- The error-handling types must depend on no peer or sub-package — they are a leaf that everything
  may reference and that references nothing domain-specific.
- Both directions are enforced by ArchUnit in `PackageDependencyRulesTest`: the
  `controller advice must live only in the top-level package` test pins the single mapping seam to
  the exactly-top-level `iterator.nucleus` package, and the
  `the top-level package must not depend on any feature package` test keeps the error catalogue a
  leaf by forbidding any dependency from the top-level package onto `audit`, `kafka`, `idempotency`,
  or `schedule`. Together they hold the catalogue in one place without needing a blanket
  where-exceptions-may-live rule.

**Pitfalls:**
- A sub-package defining a local *mapped* exception and its own handler splinters the catalogue and
  the mapping seam; the closed `NucleusErrorCode` vocabulary loses its meaning the moment a second
  code source exists. (This is what the single-advice ArchUnit rule prevents.)
- Letting an error-handling type import a sub-package type to enrich an error with domain detail
  inverts the dependency and risks a cycle. Carry such detail as data on the exception, not as a typed
  dependency on the domain package. (This is what the top-level-is-a-leaf ArchUnit rule prevents.)
- Conversely, hoisting a genuinely internal control-flow exception into the top-level package merely
  to satisfy a misremembered "all exceptions live at the top" rule couples the catalogue to a
  concern's private failure signalling for no benefit.

## Extension Points

Two on-pattern extensions exist and require no change to the seam's structure:

The first is a **new internal error condition** — the common case. Add a value to `NucleusErrorCode`
and throw `NucleusInternalErrorException` with it at the detecting site. `ErrorHandler` already maps
the exception type, so no advice change is needed; the new code flows through to a 500 `NucleusError`
automatically.

The second is a **new exception-to-status mapping** — needed when a failure is not a 500. Add the
new exception type (carrying a `NucleusErrorCode`) and a sibling `@ExceptionHandler` method on
`ErrorHandler` annotated with the appropriate `@ResponseStatus`. The client-validation / 4xx case
below is the first anticipated instance of this, but it must be built under a red test before it is
documented here (see Findings).

## Relationships

This concern is depended upon by every concern that can fail and wants a typed response;
**idempotency** (`docs/design/idempotency.md`) is the current sole consumer and its throw site is
the reference example. It composes with **serialization** (`docs/design/serialization.md`): the
`NucleusError` body is written by the shared `Serialization.mapper`, and the idempotency throw is
itself triggered by a Jackson (de)serialisation failure. It does not depend on any domain concept
and serves no single architecture document; it is a cross-cutting infrastructure seam.

CLAUDE.md's "Errors" convention paragraph is the compressed headline for this document. It now
describes only `NucleusInternalErrorException` → 500 and records that 4xx/validation handling is
deliberately left open, pointing here for the full statement; the two are in agreement, and this
document is the authoritative full statement.

## ADR References and Candidates

No ADRs exist yet (the reset removed them). Two decisions embodied here foreclose reasonable
alternatives and are ADR candidates:

- **Typed exceptions translated by a single `@ControllerAdvice` as the one error-mapping seam.** The
  alternative — per-controller error assembly, or `ResponseEntity` returns threaded through the call
  stack — is foreclosed. Worth an ADR because it fixes where error-to-HTTP translation may live.
- **A closed `NucleusErrorCode` enum as the machine-readable error vocabulary.** The alternative —
  open string codes, or relying on HTTP status alone — is foreclosed. Worth an ADR because it fixes
  the client's failure contract as an additive, enumerated set.
- **The top-level package owns the HTTP-mapped exception catalogue and the single error-mapping seam;
  sub-packages reference but never define mapped error types.** Worth an ADR because it fixes a
  package-dependency direction — everything may depend on the error catalogue, the catalogue depends on
  nothing domain-specific — now enforced by the `controller advice must live only in the top-level
  package` and `the top-level package must not depend on any feature package` tests in
  `PackageDependencyRulesTest`. The ADR would also record the scoping decision: the constraint binds
  HTTP-mapped exceptions, not internal control-flow exceptions.

## Open Questions and Findings

**The skeleton has no client-error (4xx) handling at all.** This remains the dominant finding, though
it is no longer a documentation-versus-code divergence: it is a deliberate gap. `ErrorHandler`
registers exactly one `@ExceptionHandler`, for `NucleusInternalErrorException` (500). There is no
`NucleusValidationException` type, no `NucleusViolation` type, no bean-validation → validation-exception
path, no `twoDecimalPlaceViolation` / `sevenDecimalPlaceViolation` helpers in `Extensions.kt`, and no
400 path anywhere in `src/`. The domain reset removed them. The consequence is concrete: any unhandled
exception — including bad client input — falls through to Spring's default error handling and never
becomes a `NucleusError`.

This document deliberately does **not** invent the validation pattern. Per the technical-designer
rule, a pattern is captured only after it exists in code. The validation / `NucleusViolation` path
must be re-grown in a TDD session (against its owning architecture) and harvested afterward, at which
point this document gains a "Client validation error" pattern under Patterns and the second Extension
Point above is realised. CLAUDE.md's "Errors" paragraph now describes only
`NucleusInternalErrorException` and records that 4xx/validation handling is deliberately left open, so
it no longer overstates the skeleton; the validation path itself still has to be built, against its
owning architecture, before it is documented here. The shape of that solution must not be pre-designed
— neither this document nor CLAUDE.md should presume a `NucleusValidationException`/`NucleusViolation`
form.

**The catalogue-isolation rule is now enforced.** The earlier gap — that nothing prevented a
sub-package from declaring its own advice or the error catalogue from acquiring a domain dependency —
is closed. `PackageDependencyRulesTest` carries two ArchUnit rules that hold the seam: `controller
advice must live only in the top-level package` requires every `@ControllerAdvice` to reside in the
exactly-top-level `iterator.nucleus` package, and `the top-level package must not depend on any feature
package` forbids the top-level package (where the error catalogue lives) from depending on `audit`,
`kafka`, `idempotency`, or `schedule`. There is deliberately *no* rule declaring that all exception
types must live at the top level — that would wrongly capture internal control-flow exceptions such as
`ScheduledTaskException`, which never reach the advice.

**Forward note (do not build now).** Today's leaf rule enforces catalogue isolation indirectly: it
keeps the top-level package free of domain dependencies rather than naming the HTTP-mapped exceptions
directly, so it cannot by itself say "a mapped exception must not be declared in a sub-package". When
the deferred 4xx/validation path is grown, the HTTP-mapped exceptions may come to share a sealed base
type; at that point a precise rule — "types assignable to the sealed HTTP-mapped-exception base reside
only in `iterator.nucleus`" — becomes expressible, sharper than the current leaf rule and able to
distinguish mapped exceptions from internal control-flow ones by type rather than by package
dependency. This is noted only so the option is not forgotten; it is not to be designed until that path
exists in code.

**Secondary:** `NucleusError` exposes only `{ code, message }`. If clients come to need field-level
detail, that is a shape change to the response DTO to be decided when the client-error path is built —
not a change to make speculatively now.