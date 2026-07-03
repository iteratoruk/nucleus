# Technical Design: Idempotency

## Purpose

This concern gives a write endpoint at-most-once effect under client retries: the same logical
operation submitted twice with the same idempotency key performs its work once and returns the
same response both times. It provides a persistent record of `(operationId, idempotencyKey) →
responseBody` and two operations over it — look up an existing response and replay it, or record a
freshly computed one. This document governs how a write endpoint opts into that behaviour and the
constraints that keep the guarantee sound. It does not govern how endpoints are wired, how the
response DTO is shaped, or where in a request lifecycle the check sits — those belong to the story
that adopts the pattern. The persistence, serialization, and error-handling machinery it stands on
are documented in `docs/design/persistence.md`, `docs/design/serialization.md`, and
`docs/design/error-handling.md` respectively; this document references them and does not restate
them.

## Vocabulary

**`IdempotentOperation`** — the JPA entity recording one performed operation. It carries four
intrinsic `val` fields: `operationId` (the logical operation's stable identifier), `idempotencyKey`
(the client-supplied key), `uri` (stored for traceability), and `responseBody` (the operation's
response serialized to a JSON string). It extends `AbstractJpaEntity`; identity, versioning, and
auditing columns come from there (see `docs/design/persistence.md`).

**`IdempotentOperationRepository`** — `AbstractJpaRepository<IdempotentOperation>` with one derived
finder, `findByOperationIdAndIdempotencyKey`, returning the row or `null`.

**`IdempotencyService`** — the service exposing `findExistingResponse` and `record`. This is the
surface an endpoint touches directly when it brackets the work by hand; the declarative path below
sits on top of the same two methods.

**`@Idempotent`** — the function-target annotation a controller method wears to opt into declarative
idempotency. It carries one optional `operation` string; left blank, the `operationId` is derived
from the method (`SimpleClassName.methodName`).

**`IdempotencyAspect`** — the `@Aspect` `@Component` that brackets every `@Idempotent` method with
store-and-replay via an `@Around("@annotation(idempotent)")` advice. It reads the header, derives the
`operationId`, resolves the response type from the method signature, short-circuits on a hit, and on a
miss performs the work and records it through `recordOrReplay`.

**`NucleusHeaders.IDEMPOTENCY_KEY`** (`"Idempotency-Key"`) — the request header carrying the key.

**`NucleusErrorCode.IDEMPOTENT_OPERATION_RESPONSE_UNREADABLE`** — the error code raised when a
stored response cannot be deserialized (see `docs/design/error-handling.md`).

**`V002__create_idempotent_operation_table.sql`** — the migration creating the
`idempotent_operation` table and its composite unique constraint.

## Patterns

### Pattern: Store-and-replay idempotency

**Problem:** A write endpoint must be safe to retry. A client that resubmits after a timeout, a
lost response, or a network fault must not cause the work to happen twice, and must receive the
original response rather than a fresh one or an error. The requirement is at-most-once execution
per idempotency key; the identity of "same operation" is the domain's, and the endpoint that adopts
this pattern names it.

**Approach:** The endpoint takes the `Idempotency-Key` request header
(`NucleusHeaders.IDEMPOTENCY_KEY`) and picks a stable `operationId` string naming the logical
operation. Before doing any work it calls
`findExistingResponse(operationId, idempotencyKey, ResponseType::class)`. On a hit the service
returns the deserialized prior response, typed to `T`, and the endpoint returns it verbatim,
short-circuiting the work entirely. On a miss (`null`) the endpoint performs the work, then calls
`record(operationId, idempotencyKey, uri, response)`, which serializes the response with
`Serialization.mapper.writeValueAsString` (see `docs/design/serialization.md`) and persists it as a
new `IdempotentOperation` row. The stored form is the full response body, not a status flag or a
hash — replay reconstructs the exact response object the first call produced.

**Reference implementation:** `iterator.nucleus.idempotency.IdempotencyService` —
`findExistingResponse` and `record`; entity and repository in the same file
(`src/main/kotlin/iterator/nucleus/idempotency/Idempotency.kt`).

**Rules:**
- Check `findExistingResponse` *before* performing the work and short-circuit on a hit; never do the
  work first and dedupe after.
- `record` only after the work has succeeded, so a stored response always corresponds to a completed
  operation.
- Pass the concrete response DTO class to `findExistingResponse`; the stored JSON must round-trip
  cleanly back into that type through `Serialization.mapper`.
- Keep the `operationId` stable across deployments for a given logical operation — it is half the
  identity of every record and cannot be renamed without orphaning history.

**Pitfalls:**
- Storing a key or a boolean instead of the response and recomputing on replay defeats the point:
  replay must return the *original* response, which a recompute cannot guarantee once state or time
  has moved on. This is why the entity holds `responseBody`, not a marker.
- `findExistingResponse` deserializes through `readValue(String, Class<T>)`, which cannot express a
  generic container. A response type that is itself parameterised (a `List<Foo>`, a wrapper with a
  type parameter) will not round-trip through this call as written; the reference implementation
  assumes a concrete DTO. If a parameterised response is needed, that is a change to the service, not
  a call-site workaround — surface it.

### Pattern: Declarative `@Idempotent` interceptor

**Problem:** Bracketing every write controller by hand — reading the header, choosing an
`operationId`, calling `findExistingResponse` then `record` around the body — repeats the same
scaffolding at every call site and leaves the sequencing (check before work, record after success) to
each author to get right. It also leaves unsettled where in the request lifecycle the check sits. A
controller should be able to declare "this operation is idempotent" and have the mechanism applied for
it, without importing the service into its body.

**Approach:** A controller function wears `@Idempotent` (optionally naming its `operation`), and
`IdempotencyAspect` — a Spring AOP `@Around("@annotation(idempotent)")` advice — wraps the invocation.
The aspect resolves the current request via `RequestContextHolder`, reads the `Idempotency-Key` header,
and only engages when both a request and a key are present; absent either (a missing header, or a call
outside a servlet request), it calls `joinPoint.proceed()` unchanged, so the annotation is inert
without a key. When it engages, it derives the `operationId` as `idempotent.operation.ifBlank {
"${signature.declaringType.simpleName}.${signature.name}" }` and takes the response type from the
method signature (`signature.returnType.kotlin`). It then calls `findExistingResponse(operationId, key,
responseType)`: on a hit it returns the stored response and never proceeds; on a miss it calls
`joinPoint.proceed()` to run the controller body, and — for a non-null result — records it through
`recordOrReplay(operationId, key, request.requestURI, response, responseType)`. A `null` result is
returned without being recorded.

**Reference implementation:** `iterator.nucleus.idempotency.Idempotent` (the annotation) and
`iterator.nucleus.idempotency.IdempotencyAspect` — `applyIdempotency` and `recordOrReplay` — in
`src/main/kotlin/iterator/nucleus/idempotency/Idempotency.kt`. The hit/miss/passthrough behaviour is
covered end-to-end by `IdempotencyApiTest` (resubmit replays and runs once; a different key runs again;
no key runs each time), and the concurrent-violation replay by `IdempotencyAspectTest`.

**Rules:**
- The annotated method's declared return type *is* the response type resolved for replay; declare a
  concrete DTO return type, not `Any`/`Object`/`ResponseEntity<*>`, or the round-trip through
  `findExistingResponse` has nothing precise to deserialize into (the parameterised-type limitation
  below applies here unchanged).
- Keep `operation` stable once set, for the same reason `operationId` must be stable — it is half the
  identity of every record. Leaving it blank pins the id to the class-and-method name, so renaming or
  moving the method silently changes the derived `operationId` and orphans prior records; set an
  explicit `operation` where that matters.
- Do not also bracket an `@Idempotent` method by hand; the aspect already performs the
  check/record, and a manual pair inside the body would double-record.

**Pitfalls:**
- Assuming `@Idempotent` alone enforces the key. It does not — without an `Idempotency-Key` header the
  aspect passes straight through and the work runs every time (see Findings). Requiring the key is a
  separate, deferred validation concern.
- Expecting the aspect to fire outside an HTTP request (an internal or async call to the same method).
  It resolves the request from `RequestContextHolder`; with no servlet request attributes it proceeds
  unbracketed.

### Pattern: Global, permanent composite key scope

**Problem:** The key namespace has to be defined: does a key expire, is it partitioned per client,
and is the key alone the identity or is it qualified? Getting this wrong either lets a retry miss a
still-valid record (double execution) or collides two unrelated operations that happened to reuse a
key.

**Approach:** The scope is global and permanent — no TTL, no expiry sweep, no per-client partition.
A record, once written, is honoured forever. Identity is the *composite* `(operationId,
idempotencyKey)`, enforced at the database by the unique constraint
`uq_idempotent_operation_key unique ("operation_id", "idempotency_key")` in
`V002__create_idempotent_operation_table.sql`, and matched in code by the derived finder
`findByOperationIdAndIdempotencyKey`. The table stores `operation_id varchar(100)`, `idempotency_key
varchar(255)`, `uri varchar(2048)`, and `response_body text`, all `not null`, alongside the
`id`/`version`/`created_by`/`created_date` columns inherited from `AbstractJpaEntity`.

**Reference implementation:** `src/main/resources/db/migration/V002__create_idempotent_operation_table.sql`
(the composite unique constraint); `IdempotentOperationRepository.findByOperationIdAndIdempotencyKey`.

**Rules:**
- The same idempotency key under a *different* `operationId` is a different operation and must not
  collide — the composite is the identity, never the key alone. Lookups and reasoning always pair the
  two.
- There is no per-client scoping. A key is global; two clients presenting the same
  `(operationId, idempotencyKey)` are treated as the same operation. If an operation needs client
  partitioning, that partition must be folded into the `operationId`, not assumed.

**Pitfalls:**
- Treating the read-then-write in `findExistingResponse`/`record` as the sole guard against duplicates
  is a race: two concurrent first-time requests with the same key can both miss the read and both
  attempt `record`. The unique constraint is the real guarantee — the second `save` fails on it (a
  `DataIntegrityViolationException`) rather than writing a duplicate. The declarative path defends this
  in `IdempotencyAspect.recordOrReplay`, which catches the violation and replays the now-stored
  response so both callers converge on one; the raw `IdempotencyService` does not, so an endpoint that
  brackets by hand must wrap `record` the same way. This converges the *response* and guarantees a
  single *record*, but not a single *execution* — under a genuine concurrent first-write both bodies
  can run before either records (see Findings).
- Assuming a key becomes free to reuse after some interval. It does not — the scope is permanent by
  design; there is no expiry.

### Pattern: Corrupt stored response is a failure, never a cache miss

**Problem:** A stored `responseBody` might be unreadable — a schema drift in the response DTO, a
truncated write, a manual edit. The dangerous failure mode is to treat "cannot read the stored
response" as "no stored response" and re-execute the write, which silently breaks idempotency.

**Approach:** `findExistingResponse` distinguishes the two cases structurally. A *missing* row
returns `null` (a genuine miss). A *present but unreadable* row — `Serialization.mapper.readValue`
throwing `JacksonException` — is caught and rethrown as
`NucleusInternalErrorException(code = IDEMPOTENT_OPERATION_RESPONSE_UNREADABLE, ...)`, surfacing as a
500 through `ErrorHandler` (see `docs/design/error-handling.md`). The corrupt case never returns
`null` and so never falls through to re-execution.

**Reference implementation:** the `try/catch (e: JacksonException)` in
`IdempotencyService.findExistingResponse`.

**Rules:**
- A stored-but-unreadable response is a 500, full stop. It must never be coerced to `null` or an empty
  result and must never trigger a re-run of the work.
- Catch `JacksonException` specifically for the deserialization failure; do not broaden to a catch-all
  that would also swallow unrelated failures.

**Pitfalls:**
- Wrapping the whole method in a `runCatching { ... }.getOrNull()` or similar would collapse the
  corrupt case into the miss case — exactly the silent re-execution this pattern exists to prevent.

## Extension Points

A new write operation opts in without touching this concern's code. The ordinary path is declarative:
annotate the controller function with `@Idempotent` (naming `operation` if the derived
class-and-method id is not wanted), declare a concrete response DTO as its return type, and
`IdempotencyAspect` brackets the rest. No enum value, entity, or migration change is required; the
mechanism is fully generic over `operationId` and response type. Where a handler cannot carry the
annotation — the check must sit somewhere other than the method boundary, or the response type is
parameterised — an endpoint can still bracket the work by hand against `IdempotencyService`:
`findExistingResponse(operationId, key, ResponseType::class)` first, returning the hit if present;
otherwise perform the work and `record(operationId, key, uri, response)` (wrapping `record` in the same
`recordOrReplay` catch the aspect uses if it needs the concurrent-first-write convergence).

The concern only grows if the response type must become parameterised (a service change, see Findings),
if the `Idempotency-Key` header must be *required* rather than optional (the deferred validation path,
see Findings), or if single-execution under a genuine concurrent first-write must be guaranteed (a
claim-first change, see Findings).

## Relationships

Depends on `docs/design/persistence.md` (`IdempotentOperation` extends `AbstractJpaEntity`;
`IdempotentOperationRepository` extends `AbstractJpaRepository`; the `id`/`version`/`created_by`/
`created_date` columns and identity semantics), `docs/design/serialization.md` (`Serialization.mapper`
for `writeValueAsString`/`readValue`, and the `BigDecimal`-as-string rule that governs how monetary
responses round-trip), and `docs/design/error-handling.md`
(`NucleusInternalErrorException`, the `NucleusErrorCode` enum, and the `ErrorHandler` mapping to a
500). No documented concern currently depends on idempotency, because no production write endpoint yet
adopts it (see Findings). CLAUDE.md's Idempotency paragraph has since been updated to lead with the
`@Idempotent` annotation and its aspect; the authoritative statement is here.

## ADR References and Candidates

Two decisions embodied here foreclose reasonable alternatives and are candidates for ADRs (unwritten):

- **Store the full response and replay it, rather than store only a key and recompute.** The entity
  holds `responseBody`; replay returns the original response verbatim. The alternative — record only
  that an operation occurred and recompute the response on replay — is cheaper in storage but cannot
  guarantee the replayed response matches the original once underlying state or time has advanced.
- **Global, permanent key scope with no TTL and no per-client partition.** Records live forever and
  the namespace is not partitioned by client. The alternatives — an expiring scope, or a per-client
  namespace — trade unbounded growth or cross-client key reuse against the simplicity and absolute
  guarantee of a permanent global composite key.

## Open Questions and Findings

- **No production endpoint adopts the mechanism yet.** `IdempotencyAspect` is now the standing caller
  of `IdempotencyService`, and `IdempotencyApiTest` exercises the whole path through a test-profile
  `IdempotencyProbeController`, so the check-then-record sequencing is witnessed under test rather than
  only asserted. What is still absent is a *domain* write endpoint wearing `@Idempotent` in
  `src/main`; the first such story is where the pattern meets a real operation.
- **Single execution is not guaranteed under a genuine concurrent first-write.**
  `IdempotencyAspect.recordOrReplay` catches the `DataIntegrityViolationException` and replays the
  now-stored response, so the concurrency contract now delivers *response-consistency* (both callers
  converge on one response) and a *single record* (the unique constraint admits one row). It does not
  deliver *single execution*: the check-then-act is still non-atomic, so two concurrent first requests
  can both miss `findExistingResponse` and both run the controller body before either records — only
  the second `record` loses, and only its response is discarded. Closing this needs a *claim-first*
  design: insert a placeholder row (or take a lock) on the `(operationId, idempotencyKey)` before the
  work, so the losing request blocks or replays instead of executing. That is a future enhancement, an
  architecture/TDD decision, not resolved here.
- **Idempotency passes through when no key is present.** Both the aspect and any hand-bracketed
  endpoint treat a missing `Idempotency-Key` header as "not idempotent" and let the work run every time
  (`IdempotencyApiTest`'s no-key case asserts the body runs twice). Making the key *required* — a 4xx
  when a write endpoint is called without one — belongs to the deferred validation path
  (`NucleusValidationException`/`NucleusViolation`), not to this concern as written; until that path
  exists, `@Idempotent` is opt-in per request, not enforced.
- **`findExistingResponse` cannot deserialize a parameterised response type.** It uses
  `readValue(String, Class<T>)` via `KClass.java`, which erases generics; the aspect inherits this
  because it resolves the response type from the method's declared return type. A response DTO that is
  itself generic (e.g. a `List<T>` or a parameterised wrapper) will not round-trip. If such a response
  is required, the service needs a `TypeReference`-based overload — flagged for the story that hits it.
- **The idempotency package is self-contained, now enforced.** It references only the top-level parent
  package (`AbstractJpaEntity`, `AbstractJpaRepository`, `Serialization`, `NucleusHeaders`, and the
  error types `NucleusInternalErrorException`/`NucleusErrorCode`) and no peer sub-package. This is no
  longer only convention: `PackageDependencyRulesTest`'s `idempotency must not depend on peer feature
  packages` rule asserts that `iterator.nucleus.idempotency..` depends on no `audit`, `kafka`, or
  `schedule` package. Keeping it self-contained is what lets the declarative `@Idempotent` support be
  applied across features without dragging domain packages into idempotency.