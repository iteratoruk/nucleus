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
only surface a write endpoint should touch.

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
  `DataIntegrityViolationException`) rather than writing a duplicate. An endpoint relying on
  idempotency under concurrency must expect and handle that constraint violation; it is not defended
  in the service as written (see Findings).
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

A new write operation opts in without touching this concern's code: pick a stable `operationId`
string for the operation, read the `Idempotency-Key` header via `NucleusHeaders.IDEMPOTENCY_KEY`, and
bracket the work — `findExistingResponse(operationId, key, ResponseType::class)` first, returning the
hit if present; otherwise perform the work and `record(operationId, key, uri, response)`. No enum
value, entity, or migration change is required for a new operation; the mechanism is fully generic
over `operationId` and response type. The concern only grows if the response type must become
parameterised (a service change) or if concurrent first-writes must be handled gracefully rather than
surfacing the unique-constraint violation (also a service change).

A stronger, still-unbuilt extension is to make idempotency *declarative*. An `@Idempotent` annotation
on a REST controller function, plus an interceptor (a Spring `HandlerInterceptor` or an AOP aspect
around the annotated method), would derive the `operationId` from the handler, read the
`Idempotency-Key` header, and bracket the invocation with `findExistingResponse`/`record`
automatically — removing the explicit calls from every controller body and settling where the check
sits in the request lifecycle (which this document currently leaves to the adopting endpoint). This is
a candidate direction, not a current pattern: it introduces new behaviour and must be built under a
red test in a TDD session before it is documented here. It is called out because the store-and-replay
service is deliberately shaped to sit behind such an interceptor without change.

## Relationships

Depends on `docs/design/persistence.md` (`IdempotentOperation` extends `AbstractJpaEntity`;
`IdempotentOperationRepository` extends `AbstractJpaRepository`; the `id`/`version`/`created_by`/
`created_date` columns and identity semantics), `docs/design/serialization.md` (`Serialization.mapper`
for `writeValueAsString`/`readValue`, and the `BigDecimal`-as-string rule that governs how monetary
responses round-trip), and `docs/design/error-handling.md`
(`NucleusInternalErrorException`, the `NucleusErrorCode` enum, and the `ErrorHandler` mapping to a
500). No documented concern currently depends on idempotency, because no write endpoint yet adopts it
(see Findings). CLAUDE.md already carries the one-line rule for this concern; the authoritative
statement is here, and no CLAUDE.md edit is proposed.

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

- **No write endpoint wires the mechanism.** `IdempotencyService` has no caller in the skeleton
  (`grep` for `IdempotencyService`, `findExistingResponse`, and `.record(` finds only the definition
  site). This is consistent with the skeleton state — the machinery predates any domain endpoint — and
  is the anticipated extension point above rather than a defect. It does mean the check-then-record
  *sequencing* (check before work, record after success) is a convention this document asserts, not one
  yet witnessed at a call site; the first adopting story is where it becomes real.
- **The read-then-write is not concurrency-safe on its own.** `findExistingResponse` then `record` is a
  non-atomic check-then-act; correctness under concurrent first-writes rests entirely on the
  `uq_idempotent_operation_key` unique constraint, and the resulting `DataIntegrityViolationException`
  is not caught or translated in `IdempotencyService`. Whether that violation should surface raw, be
  mapped to a replay of the now-existing record, or become a defined `NucleusErrorCode` is an open
  question for the first endpoint that needs it — a TDD/architecture decision, not resolved here.
- **`findExistingResponse` cannot deserialize a parameterised response type.** It uses
  `readValue(String, Class<T>)` via `KClass.java`, which erases generics. A response DTO that is itself
  generic (e.g. a `List<T>` or a parameterised wrapper) will not round-trip. If such a response is
  required, the service needs a `TypeReference`-based overload — flagged for the story that hits it.
- **The idempotency package must stay self-contained.** It references only the top-level parent
  package (`AbstractJpaEntity`, `AbstractJpaRepository`, `Serialization`, `NucleusHeaders`, and the
  error types `NucleusInternalErrorException`/`NucleusErrorCode`) and no peer sub-package — verified
  against its imports. This holds today but is unenforced; an ArchUnit test asserting that the
  `idempotency` package depends only on `iterator.nucleus` (parent) and external libraries should be
  added in a task session. Keeping it self-contained is what lets the declarative `@Idempotent`
  extension above be applied across controllers without dragging domain packages into idempotency.