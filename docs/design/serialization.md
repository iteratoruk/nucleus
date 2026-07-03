# Technical Design: Serialization

## Purpose

This concern governs how Nucleus turns objects into JSON and back, and how it normalises the numeric
values — money and rates — that flow across those boundaries. Everything hangs off a single Jackson
`ObjectMapper` that is configured once and reused everywhere JSON is produced or consumed: HTTP
request and response bodies, Kafka payloads, idempotency-key response storage, and audit logging.
The document also owns the two decimal-scale normalisation helpers, because scale is a serialisation
concern here — a `BigDecimal` is written to the wire as a string and its scale is part of what the
consumer sees. It does not cover error mapping (see `docs/design/error-handling.md`), the Kafka
serdes machinery that merely consumes the mapper (`docs/design/messaging.md`), or the Redis L2 cache
codec (see the Finding below — the mapper is not wired into it).

## Vocabulary

`Serialization` (`App.kt`) is the object holding the one canonical `ObjectMapper` as `Serialization.mapper`.
`App.objectMapper()` is the `@Bean` method that returns that same instance, so the Spring-managed
`ObjectMapper` and the statically-reachable one are identical. `BigDecimalToStringSerializer` and
`BigDecimalFromStringDeserializer` (`App.kt`) are the custom Jackson `StdSerializer`/`StdDeserializer`
registered on the mapper via a `SimpleModule` to force the string wire format for `BigDecimal`.
`BigDecimal.toTwoDecimalPlaces()` and `BigDecimal.toSevenDecimalPlaces()` (`Extensions.kt`) are the
scale-normalisation extension functions. `Uris.API_V1` (`"/api/v1"`) and `NucleusHeaders`
(`CLIENT_ID = "X-Client-ID"`, `IDEMPOTENCY_KEY = "Idempotency-Key"`) are the shared HTTP-vocabulary
constants. These are all infrastructural; none carries a domain concept requiring an architecture
reference.

## Patterns

### Pattern: One canonical ObjectMapper

**Problem:** JSON must serialise identically wherever it is produced — an HTTP response, a Kafka
message, a stored idempotency response, an audit record. If each call site news up its own
`ObjectMapper` (or relies on a differently-configured Spring default), the same object serialises
differently on different boundaries: dates as timestamps in one place and ISO strings in another,
`BigDecimal` as a number here and a string there. Divergence is silent and only shows up as a
consumer parsing failure or a precision bug.

**Approach:** `Serialization.mapper` is built once as a singleton and is the authoritative mapper for
the whole application. It is constructed with `findAndRegisterModules()` (picks up the Kotlin and
JavaTime modules from the classpath), then configured with four deliberate settings and one custom
module:

- `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false` — tolerate forward-compatible payloads
  that carry fields this service does not know.
- `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS = false` — dates as ISO-8601 strings, not epoch
  numbers.
- `SerializationFeature.WRITE_DATES_WITH_ZONE_ID = true` — retain the zone id on zoned temporals.
- `JsonInclude.Include.NON_NULL` — omit null fields from output.
- A `SimpleModule` registering the `BigDecimal` string serializer/deserializer pair (see next
  pattern).

`App.objectMapper()` returns this very instance as the Spring `@Bean`, so Spring MVC's message
converters, any injected `ObjectMapper`, and any direct `Serialization.mapper` reference are the same
configured object. There is exactly one mapper in the process.

**Reference implementation:** `App.kt` — `object Serialization` and `App.objectMapper()`. The
canonical consumer that reaches for it statically rather than by injection is
`kafka/Kafka.kt` — `NucleusSerializationBase.serialize`/`deserialize`, which call
`Serialization.mapper.writeValueAsBytes` / `readValue`.

**Rules:** Never construct a bare `ObjectMapper()` anywhere in `src/main`. Reach for the shared
mapper — by injecting the `ObjectMapper` bean where you are in a Spring-managed component, or by
referencing `Serialization.mapper` directly where you are not (serdes, static utilities). Any new
serialisation boundary — a new Kafka serde, idempotency storage, audit logging — uses this mapper.
Configuration changes to serialisation behaviour are made in one place: `object Serialization`.

**Pitfalls:** Newing up a local `ObjectMapper` "just for this one call" reintroduces the divergence
this pattern exists to prevent, and it will lack the `BigDecimal`-as-string module, so any monetary
value it touches silently reverts to a JSON number. Equally wrong is declaring a second `@Bean
ObjectMapper` with different configuration; there must be one. Registering an additional Jackson
module by mutating the shared mapper from a call site is also wrong — module registration belongs in
the `Serialization` object where the full configuration is visible in one place.

### Pattern: BigDecimal on the wire is a string, not a number

**Problem:** Monetary and rate values are `BigDecimal` for exactness. JSON numbers are not exact: a
consumer parsing a JSON number into an IEEE-754 double (the default in JavaScript and many clients)
can lose precision and round — `0.1 + 0.2` is the canonical failure. Emitting a bank balance or an
interest rate as a JSON number invites a client to silently corrupt it. The value must survive the
round trip through any conformant JSON parser without loss.

**Approach:** The mapper registers a `SimpleModule` binding `BigDecimal` to a custom serializer and
deserializer. `BigDecimalToStringSerializer.serialize` writes `gen.writeString(value.toString())` —
the value is emitted as a quoted JSON string carrying its exact digits and scale.
`BigDecimalFromStringDeserializer.deserialize` reads `p.text` and constructs `BigDecimal(p.text)`,
catching `NumberFormatException` and rethrowing it as `ctxt.weirdStringException(...)` so malformed
input surfaces as a Jackson deserialization error rather than an uncaught runtime exception.

**Reference implementation:** `App.kt` — `BigDecimalToStringSerializer`, `BigDecimalFromStringDeserializer`,
and their registration in `object Serialization`.

**Rules:** All monetary and rate values are `BigDecimal` and therefore serialise as strings — this is
automatic once a value is a `BigDecimal` and the shared mapper is used. Do not override the field with
`@JsonSerialize`/`@JsonRawValue` to emit a number. Do not model money as `Double`, `Float`, or a
primitive-backed numeric type; those bypass the custom serializer and reintroduce the precision
hazard. On the read side, expect and accept the value as a quoted string; a client sending a bare JSON
number for a `BigDecimal` field is sending the wrong wire type.

**Pitfalls:** Reaching for a raw `ObjectMapper()` (see the previous pattern) loses this module, so
`BigDecimal` reverts to a JSON number and the precision guarantee is silently gone — the tests still
pass locally because the JVM's `BigDecimal` reader is exact; the corruption only appears in a
JavaScript client. Assuming `p.text` is always parseable and skipping the `NumberFormatException`
handling turns bad client input into a 500 instead of a clean deserialization error.

### Pattern: Normalise decimal scale before it crosses a boundary

**Problem:** A computed `BigDecimal` carries whatever scale its arithmetic produced — a division can
yield many decimal places, an addition can yield a ragged scale. Money must be represented to two
decimal places and rates to seven; and because the value is serialised as a string carrying its exact
scale, an un-normalised value leaks its ragged scale to every consumer and produces inconsistent
records for what should be equal amounts.

**Approach:** Two extension functions fix the scale with banker's rounding: `toTwoDecimalPlaces()` is
`setScale(2, RoundingMode.HALF_EVEN)` for monetary values, and `toSevenDecimalPlaces()` is
`setScale(7, RoundingMode.HALF_EVEN)` for rates. `HALF_EVEN` (round half to even) is chosen over
`HALF_UP` because it is unbiased over many roundings, the standard convention for financial rounding.

**Reference implementation:** `Extensions.kt` — `BigDecimal.toTwoDecimalPlaces`, `BigDecimal.toSevenDecimalPlaces`.

**Rules:** Normalise a monetary value with `toTwoDecimalPlaces()` and a rate with
`toSevenDecimalPlaces()` before persisting it or serialising it. Use `HALF_EVEN` consistently — do
not `setScale` with a different `RoundingMode` at a call site, and do not truncate. Two decimal places
is the money scale and seven is the rate scale throughout; do not introduce a third scale without a
design decision.

**Pitfalls:** Calling `setScale` inline with an ad-hoc rounding mode fragments the convention and can
mix `HALF_UP` and `HALF_EVEN` across the codebase, producing amounts that differ by a penny depending
on which path computed them. Relying on the serializer to round — it does not; `toString()` emits
whatever scale the value has, so an un-normalised `BigDecimal` serialises its full ragged precision.

### Pattern: Single source for HTTP vocabulary

**Problem:** URI prefixes and custom header names are referenced from controllers, filters, and tests
alike. If each site spells `"/api/v1"` or `"X-Client-ID"` as a string literal, a typo produces a
route or header mismatch that no compiler catches, and a change to the prefix means hunting literals.

**Approach:** `Uris.API_V1` holds the `"/api/v1"` base path and `NucleusHeaders` holds `CLIENT_ID`
(`"X-Client-ID"`) and `IDEMPOTENCY_KEY` (`"Idempotency-Key"`) as `const val`s. Every reference to
these strings goes through the constant.

**Reference implementation:** `App.kt` — `object Uris`, `object NucleusHeaders`.

**Rules:** Reference the constant, never the literal, for the API base path and for the `X-Client-ID`
and `Idempotency-Key` header names. New shared HTTP constants of the same kind belong in these objects.

**Pitfalls:** Duplicating the literal in a controller mapping or a test request builder; it drifts from
the constant and breaks silently.

## Extension Points

A new serialisation boundary (a new Kafka serde, a new store of serialised payloads) extends this
concern by consuming `Serialization.mapper` — inject the `ObjectMapper` bean or reference the object
directly; do not build a mapper. A new Jackson customisation that must apply application-wide (a new
custom serializer, a global feature toggle) is added inside `object Serialization`, either by chaining
another `.configure(...)` or by extending the registered `SimpleModule`. A new custom type wire format
follows the `BigDecimal` precedent: a `StdSerializer`/`StdDeserializer` pair registered on that module.
A new HTTP header or URI constant is added to `NucleusHeaders` or `Uris`.

## Relationships

Serialization is foundational: the idempotency store (`docs/design/idempotency.md`), audit logging
(`docs/design/audit.md`), and Kafka messaging (`docs/design/messaging.md`) all depend on the single
mapper, and the `BigDecimal`-as-string format and scale normalisation matter most on the monetary and
rate fields those concerns carry. Error handling (`docs/design/error-handling.md`) is adjacent: the
`weirdStringException` raised by the `BigDecimal` deserializer is a Jackson-level error that
`ErrorHandler` ultimately maps. The `NucleusHeaders.CLIENT_ID` constant is consumed by the persistence
concern's client-id audit filter (`docs/design/persistence.md`) and `IDEMPOTENCY_KEY` by idempotency.

CLAUDE.md already carries the compressed headline of this concern ("Use the single
`Serialization.mapper` … `BigDecimal` serialises as a JSON string … `toTwoDecimalPlaces`/
`toSevenDecimalPlaces`"). No new CLAUDE.md edit is proposed for the patterns themselves; but see the
Findings — CLAUDE.md's reference to `twoDecimalPlaceViolation`/`sevenDecimalPlaceViolation` describes
code that does not exist and should be corrected once the validation concern is settled.

## ADR References and Candidates

Three decisions here foreclose reasonable alternatives and are ADR candidates (numbered from ADR-001
upward when written; not written here):

- **`BigDecimal` as a JSON string wire format.** The alternative — emitting `BigDecimal` as a JSON
  number, possibly with `JsonParser.Feature.USE_BIG_DECIMAL_FOR_FLOATS` on the read side — is the
  Jackson default and is rejected because it exposes clients to double-precision rounding. Worth an
  ADR because it constrains the public API contract.
- **A single application-wide `ObjectMapper`.** The alternative — per-boundary mappers or reliance on
  Spring Boot's auto-configured mapper with Nucleus tweaks layered on — is rejected in favour of one
  explicitly-constructed instance that is also the bean.
- **`HALF_EVEN` at fixed scales of 2 (money) and 7 (rates).** The alternative rounding modes
  (`HALF_UP` in particular) and any other scale are foreclosed for monetary and rate values.

## Open Questions and Findings

**The Redis L2 cache configures no codec — a known, serious omission slated for correction.**
`redisson.yml` contains only a `singleServerConfig` address and configures no codec, so the Hibernate
second-level cache backed by Redisson falls back to Redisson's default codec. That default is
JDK/Java serialization unless a lighter codec is present on the classpath and explicitly selected —
and relying on Java serialization for the L2 cache is a failure waiting to happen: brittle across
class-shape changes, and a portability and security liability. The canonical `Serialization.mapper`
is *not* wired into Redisson; any claim that it is the L2-cache codec is not borne out by the
configuration. This gap is to be filled in a dedicated `docs/roles/task-author.md` +
`docs/roles/task-implementor.md` session that selects and configures an explicit Redisson codec; it
is not resolved here, and no design should assume the cache serialisation format matches the wire
format until it is.

**CLAUDE.md is ahead of the code on validation helpers.** CLAUDE.md's Serialization section states
that monetary/rate values are constrained "using the `toTwoDecimalPlaces`/`toSevenDecimalPlaces` and
`twoDecimalPlaceViolation`/`sevenDecimalPlaceViolation` helpers in `Extensions.kt`". Only the two
rounding functions exist in `Extensions.kt`; there are no `twoDecimalPlaceViolation`/
`sevenDecimalPlaceViolation` helpers and no `NucleusViolation` type in the skeleton — they were
removed in the domain reset. This document describes only what exists (the two rounding helpers). The
absence of the validation/violation path is owned as a Finding by `docs/design/error-handling.md` (no
`NucleusValidationException` / `NucleusViolation` / bean-validation path exists). CLAUDE.md's
Serialization wording has since been corrected to drop the two non-existent `*Violation` helpers (it
now references only `toTwoDecimalPlaces`/`toSevenDecimalPlaces`); the validation/violation path itself
remains to be re-grown in a TDD session and must not be pre-designed here.