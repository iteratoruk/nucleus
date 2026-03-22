# Domain Model: Idempotency

## Bounded Context

**Idempotency** is responsible for recording accepted operations and detecting repeated
submissions of the same operation so that they can be answered without reprocessing.
It owns the persistence of idempotent operation records and the service logic that
checks for and stores those records.

This context is cross-cutting. It does not belong to the parameter configuration
bounded context, the account features bounded context, or any other domain area — it
serves all of them. It owns no domain concepts belonging to its consumers. It has no
knowledge of what an account feature is, what a classification code means, or what
the business effect of any given operation is. It knows only that an operation with a
given identity was accepted and that a particular response was produced.

This context explicitly does not own: the decision of which operations are idempotent
(that is the consumer's responsibility), the domain validity of a submitted request
(validation precedes recording and is the consumer's concern), the request routing or
HTTP mechanics through which an idempotency key is supplied, or any interpretation of
what the stored response means to the domain that produced it.

The foundational constraint on this context is that it depends on nothing within the
Nucleus bounded context graph. No other bounded context package is a dependency of
this one. This constraint is structural, not advisory, and is enforced by
`BoundedContextDependencyTest`. See ADR-016.

---

## Ubiquitous Language

**Idempotency key.** An opaque string supplied by the client to identify a distinct
request attempt. The idempotency context assigns no meaning to the content of the key.
The client is responsible for generating keys that are unique per distinct intended
operation. Two requests carrying the same idempotency key for the same operation ID
are treated as the same request.

**Operation ID.** A string constant defined by the consuming bounded context that
identifies the logical operation type being guarded. Examples: `PUT_ACCOUNT_FEATURES`.
The operation ID is the namespace within which idempotency keys must be unique. Two
idempotency keys that are identical but belong to different operation IDs do not
conflict and represent distinct recorded operations. The operation ID is not
client-supplied; it is determined by the service method in which the idempotency check
is embedded.

**Idempotent operation.** A record of an accepted operation, identified by the pair
(operation ID, idempotency key). Created at the point the original operation succeeds.
Immutable once created. Does not expire.

**Stored response.** The response body captured when the idempotent operation was
first accepted, serialised to JSON text. On a no-op resubmission, the stored response
is deserialised and returned to the caller without reprocessing the request.

**No-op resubmission.** A submission whose (operation ID, idempotency key) pair matches
an existing idempotent operation record. The idempotency check returns the stored
response immediately. No validation is performed, no write occurs, no domain events
are raised. The payload and resource target of the resubmission are not inspected.

**Idempotency check.** The first guard applied by any idempotent operation — preceding
validation and write logic. If a match is found, the stored response is returned and
the operation terminates. If no match is found, processing continues normally.

---

## Aggregates

### Idempotent Operation

**Identity:** The pair (operation ID, idempotency key). Two records are the same
aggregate if and only if both their operation ID and their idempotency key are
identical. This uniqueness is enforced at the database level by a unique constraint on
the `(operation_id, idempotency_key)` columns of the `idempotent_operation` table.

**Invariants:**

1. Once an idempotent operation is recorded, it is immutable. The stored response is
   never updated or replaced. A no-op resubmission returns the original stored
   response regardless of any change in the request payload.

2. At most one idempotent operation may exist for a given (operation ID, idempotency
   key) pair. Attempting to record a second operation for the same pair is a violation
   of this invariant and will be rejected by the database constraint. In practice this
   cannot arise through normal service flow, as the idempotency check precedes the
   record call.

3. An idempotent operation record does not expire. There is no TTL field and no
   deletion mechanism. The guarantee that a recognised key returns the original
   response is permanent. See ADR-014.

4. Idempotency keys are not scoped to any resource dimension. The same key submitted
   for a different classification code, account, or other resource identity — within
   the same operation ID — will be recognised as a no-op resubmission and will return
   the stored response from the original request. The operation ID is the only
   namespace; the resource target is not part of the record's identity.

**Entities within this aggregate:** None. The idempotent operation record is a flat,
immutable value: once created, it has no internal state that evolves.

**Value objects:**

- **Operation ID.** An uppercase string constant identifying the logical operation
  type. Defined by the consuming service, not by the client.

- **Idempotency Key.** An opaque client-supplied string. No structural constraints
  beyond a maximum length (255 characters, enforced at the schema level). The
  idempotency context assigns no meaning to its content.

- **Stored Response.** JSON text representing the serialised form of the original
  response body. The idempotency context treats this as opaque text; interpretation
  is the responsibility of the consuming context at deserialisation time. See ADR-015.

- **URI.** The request URI of the original accepted operation. Stored as an audit
  record of where the original request was directed. Not used in idempotency key
  matching or response retrieval.

**Domain events produced:** None. The idempotency context raises no domain events.
Recording an idempotent operation and detecting a no-op resubmission are both
internal, side-effect-free operations from the perspective of the wider domain.

**Domain events consumed:** None. The idempotency context does not react to events
from other bounded contexts.

---

## Context Relationships

**Account Features context (consumer — as of NUC-004):**
The Account Features context consumes `IdempotencyService` to guard the
`PUT /account-features/{classificationCode}` operation. It supplies the operation ID
constant `PUT_ACCOUNT_FEATURES` and passes the client-supplied idempotency key. The
Account Features context is downstream of the check and upstream of the record: it
performs the check at entry to the service method, executes its own domain logic if
no match is found, and records the result on success. The integration is a direct
service dependency.

**Architectural constraint — dependency isolation:**
The idempotency package depends on nothing within the Nucleus bounded context graph.
It is the foundational layer of that graph. All other bounded context packages may
depend on it freely; it may not depend on any of them. This constraint is enforced
by `BoundedContextDependencyTest` and recorded in ADR-016. Any future development
of the idempotency context that would require a dependency on another bounded context
is prohibited without an ADR explicitly revisiting this decision.

---

## Open Questions

**OQ-1: Storage growth at production scale.**

Idempotent operation records are permanent and the table has no deletion or archival
mechanism. For a pre-production system this carries no operational risk, but at
production scale the table will grow without bound. The question of whether a
retention policy, archival strategy, or TTL-based expiry is appropriate — and at
what scale — must be addressed before production readiness. Any change to the
permanent, no-expiry guarantee requires an architecture session and supersession of
ADR-014.

**OQ-2: Registration of new consuming contexts.**

There is no central registry of operation IDs or of the response types stored against
them. As new bounded contexts introduce idempotent operations, their operation ID
constants and associated response types are defined locally within those contexts.
The backward compatibility obligation described in ADR-015 applies to all of these,
but there is currently no mechanism to enumerate them or assess the impact of a
serialisation-breaking change across all consumers. The question of whether a
centralised registry or convention-based discovery is needed should be revisited as
the number of idempotent operations grows.