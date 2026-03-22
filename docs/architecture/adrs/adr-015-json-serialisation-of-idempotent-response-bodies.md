# ADR-015: JSON Serialisation of Idempotent Response Bodies

**Date:** 2026-03-22
**Status:** Accepted

## Context

The idempotency context must store an arbitrary response body at the time an operation
is first accepted, and retrieve and return it on subsequent no-op resubmissions. The
idempotency context is cross-cutting and dependency-isolated (ADR-016): it may not
depend on the specific response types of the bounded contexts it serves. The
persistence mechanism must therefore be capable of storing any response type without
importing or referencing that type directly.

Several storage strategies were available:

**Typed column storage.** The response body could be stored as a structured database
column for each response type — a separate table per operation type, with typed columns
matching the response structure. This would require the idempotency schema to change
whenever a new idempotent operation type was added, and would introduce direct
coupling between the idempotency context and the domain types of its consumers.

**Binary serialisation.** The response body could be serialised to a binary format
(e.g., Java serialisation, Protocol Buffers) and stored as a blob. This would decouple
the schema from the response structure but would introduce a harder binary
compatibility constraint: any change to the serialised type would require a migration
plan before existing records could be read.

**JSON text serialisation.** The response body is serialised to JSON text using the
shared `Serialization.mapper` and stored in a `text` column. On retrieval, the stored
JSON is deserialised to the declared target type supplied by the consuming context.

## Decision

Idempotent response bodies are serialised to JSON text at the time of recording and
stored in the `response_body` column of the `idempotent_operation` table. On
retrieval, `findExistingResponse` deserialises the stored JSON to the target type
declared by the caller. The `Serialization.mapper` shared across Nucleus is used for
both operations.

The idempotency context treats the stored response body as opaque text. It has no
knowledge of the structure of any specific response type. Type knowledge is supplied
at the call site by the consuming context via the `type: KClass<T>` parameter.

If deserialisation fails — because the stored JSON is not compatible with the declared
target type — `findExistingResponse` throws
`NucleusInternalErrorException(IDEMPOTENT_OPERATION_RESPONSE_UNREADABLE)`. This
surfaces the backward compatibility failure as an explicit internal error rather than
a silent data corruption.

## Consequences

**Positive:**

The idempotency context requires no dependency on any specific response type. New
idempotent operation types can be added by new consuming contexts without any change
to the idempotency context's schema or service code.

JSON text is human-readable in the database, which aids debugging and operational
investigation without tooling.

The shared `Serialization.mapper` ensures consistency between serialisation at record
time and deserialisation at retrieval time, under the assumption that the mapper's
configuration does not change in a breaking way.

**Negative:**

A backward compatibility obligation is introduced for every response type used by an
idempotent operation. If a response type is changed in a way that is not compatible
with previously stored JSON — for example, by renaming a field, changing its type, or
removing a required field — `findExistingResponse` will throw
`NucleusInternalErrorException(IDEMPOTENT_OPERATION_RESPONSE_UNREADABLE)` for any
stored key whose response body was written against the old type. In the pre-production
state this carries no operational risk, because no stored records exist in production.
Once Nucleus reaches production readiness, this obligation must be assessed before any
breaking change to a response type used by an idempotent operation is introduced.

**Risks:**

A breaking change to a response type deployed to production without a migration
strategy will cause idempotency retrieval failures for all affected keys. Clients
retrying operations with affected keys will receive a 500 error rather than the
original stored response. This is the most significant operational risk of this design.

The mitigation is a process constraint: before any change to a response type used by
an idempotent operation is introduced, the change must be assessed for
deserialisation compatibility with the stored bodies of existing records. Where a
breaking change is unavoidable, a migration strategy — which may include a one-off
data migration, a versioned response type, or a key invalidation policy — must be
agreed in an architecture session before the change is deployed.

This ADR should be reviewed, and a concrete migration strategy produced, at the point
Nucleus approaches production readiness.

## Alternatives Considered

**Typed column storage per operation type.** Rejected because it couples the
idempotency schema to the domain types of consuming bounded contexts, violating the
dependency isolation constraint (ADR-016) and requiring schema changes whenever a new
idempotent operation type is introduced.

**Binary serialisation.** Rejected because the binary format would impose a harder
compatibility constraint than JSON — any change to the serialised type would typically
require full re-serialisation of all affected records — and would sacrifice the
human-readability benefit of JSON text. JSON field-level compatibility is easier to
assess and manage than binary compatibility.