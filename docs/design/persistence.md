# Technical Design: Persistence

## Purpose

This document governs how Nucleus inscribes durable state in PostgreSQL through JPA/Hibernate: how
an entity acquires identity, equality, versioning and audit stamps; which base class an aggregate
extends; how repositories are declared; how the audit author is captured from the request and wired
to the audit columns; and who owns the schema. It is the foundational persistence concern — every
other stateful concern (idempotency, audit, messaging, scheduling) builds on the base classes and
conventions described here. It does not cover serialization of persisted values (see
`docs/design/serialization.md`), nor the semantics of any particular aggregate, which belong to the
architecture documents for their bounded contexts.

## Vocabulary

`AbstractJpaEntity` (`@MappedSuperclass`) is the identity-and-audit base for immutable entities.
`AbstractMutableJpaEntity` extends it with last-modified stamps for entities whose rows are updated
in place. `AbstractJpaRepository<T>` is the `@NoRepositoryBean` marker interface over Spring Data's
`JpaRepository<T, Long>` that concrete repositories extend. `ClientIdRequestAttributeFilter` and
`ClientIdAuditor` form the audit-author path: the filter lifts the `X-Client-ID` header
(`NucleusHeaders.CLIENT_ID`) into a request attribute, and the auditor — an `AuditorAware<String>` —
reads it back for Spring Data JPA auditing, enabled by `@EnableJpaAuditing` on `App`. Schema lives in
Flyway migrations under `src/main/resources/db/migration` named `VNNN__description.sql`. Hibernate's
naming is fixed in `application.yml`: `ImplicitNamingStrategyComponentPathImpl`,
`CamelCaseToUnderscoresNamingStrategy`, and `hibernate.globally_quoted_identifiers: true`.

## Patterns

### Pattern: Identity-based entity

**Problem:** A persistent entity needs stable equality, a primary key, optimistic-lock protection and
creation-audit stamps, and it must behave correctly when Hibernate hands you a lazy proxy rather than
the concrete instance. Equality in particular must be identity equality — two rows are the same
entity when they are the same row, never when their business fields happen to coincide.

**Approach:** Extend `AbstractJpaEntity`. It is a `@MappedSuperclass` annotated
`@EntityListeners(AuditingEntityListener::class)` so audit stamps populate automatically. It provides:
a `Long id` with `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)` (the database assigns the
key on insert); a `@Version Long version` for optimistic locking; and `@CreatedBy String? createdBy`
and `@CreatedDate Instant? createdDate`, both `@Column(updatable = false)` so they are written once at
insert and never rewritten. `equals` and `hashCode` are built from `id` alone using commons-lang3
`EqualsBuilder`/`HashCodeBuilder`; `compareTo` (the class is `Comparable`) uses `CompareToBuilder` on
`id`. `equals` compares runtime type with `ProxyUtils.getUserClass(other)` so a Hibernate proxy of
the same entity type is treated as equal rather than rejected on `javaClass` mismatch. Declare an
entity's intrinsic fields as constructor `val`s and extend the base with `() : AbstractJpaEntity()`.

**Reference implementation:** `AbstractJpaEntity` in
`src/main/kotlin/iterator/nucleus/AbstractJpaEntity.kt`; the canonical extending entity is
`IdempotentOperation` in `src/main/kotlin/iterator/nucleus/idempotency/Idempotency.kt`.

**Rules:**
- Never put business fields in `equals`/`hashCode`/`compareTo`. Identity is the database `id` and
  nothing else.
- Never overwrite `createdBy`/`createdDate`; they are `updatable = false` by design.
- Compare types through `ProxyUtils.getUserClass`, not raw `javaClass`, or proxy instances break
  equality.
- Do not assign `id` by hand or change the generation strategy; the database owns key allocation.

**Pitfalls:** Including business fields in `equals` makes an entity's identity mutate as its state
changes and breaks its membership in any `Set` or map key across a modification — the classic ORM
equality bug. Using a data class (which would generate field-based `equals`/`hashCode`) reintroduces
exactly this bug; entities are ordinary classes here for that reason.

### Pattern: Choosing the base class — immutable vs mutable

**Problem:** Some entities are write-once (a recorded fact, an idempotency record); others are updated
in place and need to record who last changed them and when. The choice of base class encodes which
kind an entity is.

**Approach:** Extend `AbstractJpaEntity` for a write-once entity — it carries only creation stamps.
Extend `AbstractMutableJpaEntity` when rows are updated in place; it adds `@LastModifiedBy` and
`@LastModifiedDate`, populated by the same auditing listener on every update. The decision is not
stylistic: it follows the domain-modelling rule in `CLAUDE.md` — an aggregate stores only intrinsic,
immutable state, and values resolved from the parameter value hierarchy (accounting codes, feature
configuration) are never copied onto the entity as fields. An entity that appears to need mutability
only to hold a resolved configuration value does not need `AbstractMutableJpaEntity`; it needs the
value resolved through the hierarchy at read time. The invariant that decides mutability belongs to
the aggregate's architecture document, not here.

**Reference implementation:** `AbstractMutableJpaEntity` in
`src/main/kotlin/iterator/nucleus/AbstractJpaEntity.kt`. `IdempotentOperation` demonstrates the
immutable base. No mutable entity yet exists in the skeleton (see Findings).

**Rules:**
- Default to `AbstractJpaEntity`. Reach for `AbstractMutableJpaEntity` only when the aggregate's
  invariants genuinely permit in-place update.
- Do not add a field to make a resolved/config value intrinsic. That contradicts the domain rule.

**Pitfalls:** Choosing the mutable base "to be safe" invites later code to mutate an entity that the
domain says is immutable, and the audit stamps will silently endorse it. Model corrections as new
rows, not mutations, when the domain calls for an immutable record.

### Pattern: Repository declaration

**Problem:** Every aggregate needs a repository with the standard CRUD surface plus its own finders,
declared uniformly so the `Long`-keyed base and Spring Data wiring are never restated per entity.

**Approach:** Declare a `@Repository` interface extending `AbstractJpaRepository<YourEntity>`.
`AbstractJpaRepository<T : AbstractJpaEntity>` is a single `@NoRepositoryBean` interface over
`JpaRepository<T, Long>`: `@NoRepositoryBean` stops Spring Data instantiating the marker itself while
letting concrete sub-interfaces inherit the full CRUD contract. Add derived-query finder methods
directly to the concrete interface; Spring Data implements them from their names, returning a nullable
result where at most one row matches.

**Reference implementation:** `AbstractJpaRepository` in
`src/main/kotlin/iterator/nucleus/AbstractJpaEntity.kt`; live example
`IdempotentOperationRepository` with `findByOperationIdAndIdempotencyKey(...)` in
`src/main/kotlin/iterator/nucleus/idempotency/Idempotency.kt`.

**Rules:**
- Extend `AbstractJpaRepository<T>`, not `JpaRepository` directly, so the `Long` key type stays fixed
  in one place.
- The key type is always `Long`, matching the entity `id`.
- Annotate the concrete repository `@Repository`.

**Pitfalls:** Extending `JpaRepository<T, Long>` directly works but bypasses the project marker and
scatters the key-type decision. Do not add `@NoRepositoryBean` to a concrete repository — it would
suppress the bean you want created.

### Pattern: Audit-author propagation from the request

**Problem:** The `@CreatedBy`/`@LastModifiedBy` columns must be stamped with the acting client, but
JPA auditing runs deep in the persistence layer with no access to the HTTP request. The identity of
the author has to be carried from the inbound request down to the auditing listener.

**Approach:** Two collaborating beans bridge the gap. `ClientIdRequestAttributeFilter`, a
`OncePerRequestFilter`, reads the `X-Client-ID` header and, when present, stashes its value as a
request-scoped attribute under the same key before continuing the chain. `ClientIdAuditor`, an
`AuditorAware<String>`, reads that attribute back via `RequestContextHolder.getRequestAttributes()`
at `SCOPE_REQUEST` and returns it as the current auditor. `@EnableJpaAuditing` on `App` wires the
`AuditorAware` bean to the `AuditingEntityListener`, so the returned value lands in `createdBy` (and
`lastModifiedBy` for mutable entities). When no header is present — or when persistence happens
outside any request, e.g. a scheduled job or a Kafka consumer — the auditor returns
`Optional.empty()` and the audit-author columns are left null.

**Reference implementation:** `ClientIdRequestAttributeFilter` and `ClientIdAuditor` in
`src/main/kotlin/iterator/nucleus/AbstractJpaEntity.kt`; `@EnableJpaAuditing` on `App` in
`src/main/kotlin/iterator/nucleus/App.kt`; header constant `NucleusHeaders.CLIENT_ID` (`X-Client-ID`)
in `App.kt`.

**Rules:**
- The audit author is the `X-Client-ID` request header, resolved only through this filter/auditor
  pair. Do not read the header or set the author anywhere else.
- Persisting outside a request is legitimate; a null author is the correct outcome, not an error.

**Pitfalls:** Reading `RequestContextHolder` without the null-safe navigation the auditor uses will
throw off-request. Do not try to source the author from a thread-local set elsewhere, a security
principal, or a constructor argument; that fractures a single, deliberately narrow author source.

### Pattern: Flyway owns the schema; Hibernate never DDLs

**Problem:** Two mechanisms can create tables — Hibernate's schema export and an explicit migration
tool. Letting both act produces divergence between what Hibernate expects and what exists. The schema
must have one owner, and migrations must match the physical names Hibernate derives from the entity
mapping.

**Approach:** Flyway owns the schema. Every schema change is a migration file in
`src/main/resources/db/migration` named `VNNN__description.sql` (`V001__create_quartz_tables.sql`,
`V002__create_idempotent_operation_table.sql`), applied in version order. Hibernate is never allowed
to emit DDL; there is no `ddl-auto` generation configured, and even Quartz's schema init is disabled
(`spring.quartz.jdbc.initialize-schema: never`), confirming migrations — not the framework — create
tables. Because Hibernate reads (and, under second-level caching, addresses) tables by the physical
names its strategies produce, migration DDL must be written to those names. The naming contract is
fixed in `application.yml`: implicit names via `ImplicitNamingStrategyComponentPathImpl`, physical
names via `CamelCaseToUnderscoresNamingStrategy` (so `idempotencyKey` becomes `idempotency_key`), and
`hibernate.globally_quoted_identifiers: true`, so every identifier is double-quoted. `V002` shows the
resulting shape: `create table "idempotent_operation"` with columns `"operation_id"`,
`"idempotency_key"`, and the inherited `"id"`, `"version"`, `"created_by"`, `"created_date"`.

**Reference implementation:** `src/main/resources/db/migration/V002__create_idempotent_operation_table.sql`
as the shape to follow for an entity table; the naming/quoting block in
`src/main/resources/application.yml`.

**Rules:**
- Every schema change is a new, higher-numbered `VNNN__` migration. Never edit an applied migration.
- Write DDL to the physical names Hibernate derives — camelCase fields become underscore-separated
  columns — and quote identifiers, matching the global-quoting setting.
- Never enable Hibernate DDL generation to create or alter a table.

**Pitfalls:** Writing an unquoted or camelCase column name that diverges from Hibernate's derived
physical name yields a mapping that compiles but fails at runtime when Hibernate addresses the
expected quoted, underscored name. Amending an already-applied migration breaks Flyway's checksum
validation; add a new migration instead.

## Extension Points

A new aggregate is added on-pattern by writing an entity class extending `AbstractJpaEntity` (or
`AbstractMutableJpaEntity`), a `@Repository` interface extending `AbstractJpaRepository<T>` with any
derived finders, and a new `VNNN__` Flyway migration creating its table to Hibernate's physical
names. The auditing path requires no per-entity wiring — extending the base class and the global
`@EnableJpaAuditing` are sufficient for the audit columns to populate. Repository test coverage is
available off-the-shelf via `AbstractJpaRepositoryTest`/`AbstractMutableJpaRepositoryTest` (see
`CLAUDE.md`'s testing section).

## Relationships

This concern underpins `docs/design/idempotency.md` (its `IdempotentOperation`/repository are the
live references used here), and every other stateful concern —
`docs/design/audit.md`, `docs/design/messaging.md`, `docs/design/scheduling.md` — depends on these
base classes and the migration convention. It depends on nothing else in `docs/design/`; serialization
of persisted field values is governed separately by `docs/design/serialization.md`. It serves the
aggregate architecture documents (not yet written) that define which entities are immutable and what
their intrinsic state is. The `CLAUDE.md` Persistence paragraph is the compressed headline of this
document; no new convention edit is proposed — the existing summary is accurate.

## ADR References and Candidates

Three decisions here foreclose reasonable alternatives and are ADR candidates (unwritten; numbered
from ADR-001 upward when authored): identity-based `equals`/`hashCode` on the database-generated `id`
(over natural-key or business-field equality); Flyway owns the schema and Hibernate never emits DDL
(over `ddl-auto` generation); and the `X-Client-ID` request header as the sole audit-principal source
(over a security-context principal or an ambient thread-local).

## Open Questions and Findings

- **Database-identity equality treats all unsaved entities as equal.** `id` defaults to `0` and is
  only assigned by the database on insert, so `equals` reports any two not-yet-persisted
  `AbstractJpaEntity` instances of the same type as equal (all have `id == 0`), and their `hashCode`
  collides. This is the standard, accepted caveat of database-identity equality; it matters mainly if
  transient entities are placed in a `Set` before being saved. Recorded as a known pitfall, not a
  defect to fix.
- **The mutable base class has no realising entity in the skeleton.** `AbstractMutableJpaEntity`
  exists but no entity extends it yet; the mutable pattern is documented from the base class alone.
  Its first real use (an aggregate whose architecture permits in-place update) should confirm the
  last-modified stamps behave as described and, if anything is missing, trigger a harvest update here.