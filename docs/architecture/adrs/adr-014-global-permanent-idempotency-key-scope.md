# ADR-014: Global, Permanent Idempotency Key Scope

**Date:** 2026-03-22
**Status:** Accepted

## Context

Idempotency in Nucleus is implemented as a cross-cutting service. Any bounded context
can guard an operation by checking for a previously recorded (operation ID, idempotency
key) pair before executing its domain logic. The design of the key space — how keys
are scoped, whether they expire, and what they are uniquely relative to — determines
both the strength of the idempotency guarantee and the operational characteristics of
the mechanism.

Several design positions were available:

**Per-resource scoping.** An idempotency key could be required to be unique relative
to a specific resource — for example, relative to a classification code. Two requests
with the same idempotency key targeting different classification codes would be treated
as distinct operations. This is a common pattern in payment APIs where idempotency is
naturally scoped to a payment identifier or customer account. It would allow key reuse
across resources, reducing the generation burden on clients.

**Per-operation scoping with resource dimension.** An idempotency key could be unique
relative to both the operation type and a resource identifier. This would permit the
same key to be reused for the same resource on different operations without conflict,
while preventing cross-resource replay.

**Global, permanent keys.** An idempotency key, within an operation ID namespace, is
globally unique and permanent. It is not further scoped to any resource dimension
(classification code, account identifier, or similar). A key submitted for one
resource, if recognised, returns the original stored response regardless of whether
the resubmission targets a different resource. Keys never expire.

The decision also bears on what the service does when a recognised key arrives with a
different resource target or payload. Three positions were available: (a) return the
original stored response unconditionally, (b) reject the resubmission with a conflict
error, or (c) validate that the payload matches the original before accepting.

## Decision

Idempotency keys are scoped to the (operation ID, idempotency key) pair only. They are
not further scoped to any resource dimension. A recognised (operation ID, idempotency
key) pair causes the original stored response to be returned without reprocessing,
regardless of the resource target or payload of the resubmission. Idempotency keys do
not expire. There is no TTL field and no deletion mechanism. The guarantee is permanent.

The operation ID is the only namespace. It is a string constant defined by the
consuming service method — not supplied by the client — and identifies the logical
operation type. This prevents a key submitted for one operation type (e.g.,
`PUT_ACCOUNT_FEATURES`) from conflicting with the same string submitted for a different
operation type.

## Consequences

**Positive:**

The check is simple and unconditional. There is no resource lookup required to
evaluate whether a resubmission is valid — the key lookup alone is sufficient. The
idempotency context requires no knowledge of the domain concepts owned by its
consumers.

The guarantee is maximally strong for clients: once a key is recorded, the response
is permanent and stable. Clients can rely on the stored response without any
qualification about resource target consistency.

The absence of expiry eliminates a class of race conditions in which a client retries
after a key expires and receives a different response from a second execution of the
same operation.

**Negative:**

Clients must generate genuinely unique keys per distinct intended operation. A key
mistakenly reused for a different classification code or resource will cause a no-op
return of the previous operation's response. This is a client-side obligation that
cannot be detected by the service.

Idempotent operation records are permanent. The `idempotent_operation` table will grow
without bound over the lifetime of the system. No deletion or archival mechanism is
currently in place.

**Risks:**

Storage growth may become an operational concern at production scale. The question of
whether a retention policy is appropriate, and what its boundary conditions are, must
be assessed before production readiness. Any relaxation of the permanent, no-expiry
guarantee must be revisited in an architecture session before being introduced, as it
would affect the consistency guarantee offered to clients and could require changes to
the idempotency context's schema, service contract, and any existing clients.

The unconditional return of the stored response on a resubmission — without inspecting
payload or resource target — means that a client that supplies the wrong key for a
different intended operation receives an incorrect response silently. This is a
client-side concern, but Nucleus has no mechanism to surface it.

## Alternatives Considered

**Per-resource scoping.** Rejected because it would couple the idempotency context to
the resource identity model of each consuming bounded context, violating the dependency
isolation constraint (ADR-016). It would also require the idempotency check to perform
a resource lookup, increasing complexity without providing a meaningful additional
guarantee in the context of how Nucleus clients are expected to generate keys.

**Expiring keys with a TTL.** Rejected because a TTL introduces a window within which
the same key could be reused for a different operation and receive a different response.
This weakens the guarantee and creates a class of time-sensitive failure modes. The
storage cost of permanent keys is assessed as manageable in the pre-production state
and is recorded as an open question for production readiness rather than a reason to
weaken the guarantee.

**Payload matching on resubmission.** Rejected because it requires the idempotency
context to understand the structure of the request payload, coupling it to the domain
models of its consumers. This contradicts the cross-cutting, dependency-isolated
character of the context.