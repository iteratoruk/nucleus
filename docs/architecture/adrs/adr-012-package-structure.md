# ADR-012: Package Structure and Bounded Context Boundaries

**Date:** 2026-03-21
**Status:** Accepted

## Context

As implementation of Nucleus began with NUC-001, the first package structure decisions
were made: where to put the account-features API and what the relationship between the
`accountfeatures` and `parameters` packages should be. These decisions establish a
pattern that all subsequent bounded context implementations will follow, and the
rationale is not derivable from the code alone.

The central question is how to reflect bounded context boundaries in the package
structure of a single-module Spring Boot application. In a microservices architecture,
bounded context boundaries are enforced by deployment unit separation. In a monolith or
a monorepo structured as a single deployable, the package hierarchy is the primary
mechanism for expressing those boundaries.

Two structural options were considered:

*Feature grouping.* Packages are organised by technical concern or feature name, with
bounded context membership expressed through naming conventions or documentation rather
than structure. This is the traditional layered architecture approach.

*Bounded context packages.* Each top-level package under the root namespace corresponds
to a distinct bounded context. The package boundary is the context boundary; nothing
crosses it except through declared interfaces.

## Decision

The package structure follows bounded context boundaries. Each top-level package under
`iterator.nucleus` corresponds to a distinct bounded context or a cohesive
infrastructure concern. Packages are named after the bounded context using flat compound
names (e.g. `accountfeatures`, not `account.features`).

The dependency graph between bounded context packages must be acyclic and explicit. A
package may import from packages it depends on; it may not import from packages that
depend on it. The direction of dependency must match the domain model's context
relationships.

The current and anticipated top-level package structure is as follows:

- `iterator.nucleus.accountfeatures` — the Account Feature Catalogue bounded context.
  Owns the external API contract (`PUT /account-features/{classificationCode}`,
  `GET /account-features/{classificationCode}`), the feature catalogue definitions,
  ledger-side applicability enforcement, and the translation layer between the typed
  external feature representation and the internal parameter key-value model. Depends on
  `parameters`. Has no dependents among application packages — it is a pure ingress
  boundary.

- `iterator.nucleus.parameters` — the Parameter Configuration bounded context. Owns the
  classification code tree, parameter node aggregates, parameter values, temporal history,
  and the resolution function. Depends on nothing internal. Is depended on by
  `accountfeatures`, `accounts`, and `processing`.

- `iterator.nucleus.accounts` (anticipated) — governs Account Node Attachment lifecycle
  and the future account API. Handles `AccountOpened`/`AccountClosed` event consumption
  that creates and seals node attachment records. Depends on `parameters`.

- `iterator.nucleus.processing` (anticipated) — scheduled financial processing (e.g.
  interest accrual). Receives a resolution datetime and account identifier, resolves
  parameter values from `parameters` using parameter keys derived from the feature
  catalogue naming convention. Does not depend on `accountfeatures` at runtime — the
  shared naming convention is a definition-time contract, not a runtime dependency.
  Depends on `parameters` and `accounts`.

The dependency graph contains no cycles:

```
accountfeatures → parameters
accounts        → parameters
processing      → parameters
processing      → accounts
```

Infrastructure concerns (Kafka, Quartz scheduling, audit, error handling) are hosted in
packages within `iterator.nucleus` that do not correspond to bounded contexts. They are
not imported by bounded context packages as structural dependencies — they are
configuration and wiring concerns that the application assembles at startup.

## Consequences

**Positive:**

- Bounded context boundaries are visible in the package structure. A reader can identify
  context boundaries without reading domain documentation; the dependency rules enforce
  them at the code level.
- The acyclic dependency rule catches coupling violations at compile time (or through
  static analysis tooling). A package that imports from a downstream context is a
  structural signal that a boundary has been crossed incorrectly, not a style violation
  that can be overlooked.
- `accountfeatures` and `processing` are independent of each other. The write path
  (accountfeatures) and the execution path (processing) have no runtime coupling. A
  failure or performance degradation in the account-features API does not affect scheduled
  processing. This independence follows from the domain: the feature catalogue defines the
  parameter key namespace at definition time; processing consumes those keys directly from
  the parameter hierarchy without going through the catalogue layer.

**Negative:**

- In a single-module application, package-level boundaries are enforced by convention
  rather than by a hard compile-time barrier between modules. A developer can import
  across a context boundary without a build failure unless tooling (e.g. ArchUnit) is
  configured to enforce the dependency rules. This is a weaker boundary than separate
  Gradle subprojects or separate services.
- Flat compound package names (`accountfeatures`) are less conventional than either
  feature-group packages or nested domain names. Developers unfamiliar with the
  bounded-context framing may find the structure unexpected.

**Risks:**

- **Boundary erosion.** Without automated enforcement of the dependency rules, package
  boundaries will erode as the codebase grows and contributors take shortcuts. The
  dependency graph should be enforced by an ArchUnit rule before the number of packages
  makes manual review impractical.
- **Anticipated packages.** The `accounts` and `processing` packages are anticipated but
  not yet implemented. Their position in the dependency graph is recorded here as a
  design commitment. If the actual implementation diverges from this graph, this ADR must
  be updated to record the deviation and the reason.

## Alternatives Considered

**Nested package names (e.g. `account.features`, `account.servicing`).** Nesting bounded
context packages under a shared parent was considered for packages with a shared domain
noun (account). Rejected because a parent package (`account`) would imply cohesion between
its children that does not exist at the domain level: `account.features` and
`account.servicing` have different collaborators and different dependency directions.
A shared parent package would be a container with no semantic content — a naming
convenience that obscures the boundary rather than expressing it. Flat compound names
(`accountfeatures`) are preferred.

**Technical layer packages (controller, service, repository).** The traditional layered
architecture approach groups code by technical role rather than by bounded context.
Rejected because it inverts the primary organisational principle: the domain boundary is
more significant than the technical layer, and grouping by layer makes it difficult to
understand which code belongs to which context. The bounded-context-first structure
allows the code for a context to be read and reasoned about as a unit.

**Separate Gradle subprojects per bounded context.** Hard module boundaries enforced at
build time would provide stronger isolation than package conventions. Deferred for the
initial implementation as premature complexity while the context boundaries are still
being established. This option remains available if boundary erosion becomes a practical
problem.