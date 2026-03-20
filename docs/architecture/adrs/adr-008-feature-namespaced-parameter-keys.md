# ADR-008: Feature-Namespaced Parameter Keys

**Date:** 2026-03-20
**Status:** Accepted

## Context

The account-features API presents a strongly-typed, named representation of account
configuration to external clients (the configurer personas: Cameron, Sasha, Liam, Maya).
Nucleus translates this representation to internal parameter key-value pairs stored and
resolved by the Parameter Configuration context. The translation must be unambiguous,
safe, and encapsulated: clients submit feature names and property values; they never see
internal parameter keys.

The central safety requirement is that a feature property value submitted for one feature
must not be resolvable as a property of another feature, and a feature applicable to one
ledger side must not be resolvable on accounts of the other. In a regulated financial
system, mis-resolution of an interest rate — applying the wrong rate, or applying a rate
applicable to lending accounts to a savings account — is a financial correctness failure,
not an edge case.

Two structural mechanisms are available for enforcing this safety property:

The first is ledger-side enforcement at the API submission layer. The account-features
API determines the ledger side of the submitted classification code from its first
segment and rejects features not applicable to that ledger side before any write reaches
the Parameter Configuration context. This prevents a liability interest configuration
from being written to an asset-side node. It operates exclusively at the point of entry.

The second is the structure of the internal parameter keys themselves. If all properties
of a feature share a key prefix that is unique to that feature, the resolution function —
which treats keys as opaque strings and knows nothing of the feature catalogue — cannot
return a value stored under one feature's key in response to a query for another feature's
key. If instead two features share a key (e.g. both use `interest.rate` rather than
`assetInterest.interestRate` and `liabilityInterest.interestRate`), the resolution
function provides no protection: a value written for one feature could in principle be
resolved in the context of another if the node-level constraints were ever bypassed.

The question is whether ledger-side enforcement at the API layer is sufficient on its
own, or whether structural separation at the key level is also required.

## Decision

Parameter keys for account feature properties follow the convention
`{featureName}.{propertyName}`, where both components are camelCase and the separator is
a single period. Feature names and property names must not themselves contain periods.

Examples:
- `assetInterest.enabled`
- `assetInterest.interestRate`
- `liabilityInterest.enabled`
- `liabilityInterest.interestRate`

The feature name is the namespace prefix for all parameter keys derived from that
feature's properties. This is a structural constraint, not a naming convention that may
be selectively applied: a parameter key that does not follow this form is not a valid
feature property key.

This means that safety against cross-feature mis-resolution does not depend solely on
the ledger-side enforcement at the API layer. Both the API constraint and the key
structure independently enforce the boundary.

## Consequences

**Positive:**

- Cross-feature mis-resolution is structurally impossible without coordination between
  the API layer and the key layer. A bug or bypass in API-layer validation does not
  create a path to mis-resolution, because the resolution function operates on distinct
  keys. The system is resilient to single points of failure in its safety model.
- The feature identity of any parameter key is recoverable directly from the key string,
  without a lookup. This makes debugging, auditing, and log analysis significantly
  easier: a key of `liabilityInterest.interestRate` is immediately self-describing.
- Adding new features introduces new key namespaces with no risk of collision with
  existing features, provided the feature name is unique in the catalogue. Extensibility
  does not require restructuring the key space of existing features.
- The `enabled` property of each feature is its own key (e.g. `assetInterest.enabled`).
  Setting the enabled state of one feature at a node cannot affect the enabled state of
  any other feature at that node.

**Negative:**

- Parameter keys are longer than they would be with a flat or shared-namespace approach.
  For a system with many features and many properties, this has marginal storage and
  index implications. In the context of parameter nodes, which are not high-volume
  write targets, this is not a meaningful cost.
- The mapping from feature property to parameter key is mechanical but the rule
  (camelCase feature name, period separator, camelCase property name) must be enforced
  consistently. A deviation — a feature name in snake_case, a property name capitalised
  incorrectly — produces a key that is valid syntactically but inconsistent with the
  convention, requiring correction that may involve data migration of stored values. The
  catalogue definition must explicitly state the derived keys for each feature to make
  this verifiable.

**Risks:**

- **Convention drift over time.** As the catalogue grows, the mechanical derivation rule
  may be applied inconsistently for new features or properties if the convention is not
  enforced at catalogue definition time. The mitigation is the requirement that catalogue
  definitions explicitly state derived parameter keys — not as documentation, but as the
  authoritative source — so that deviations are detectable by inspection rather than
  only at resolution time.
- **Period in a feature or property name.** If a feature name or property name were to
  contain a period, the splitting heuristic used to recover feature and property identity
  from a key would be ambiguous. The constraint that neither component may contain a
  period must be enforced at catalogue definition time, not checked at runtime.

## Alternatives Considered

**Shared key namespace with ledger-side enforcement as the sole guard.** Under this
approach, both `assetInterest` and `liabilityInterest` would use a common key for their
interest rate property (e.g. `interest.rate`), relying on the ledger-side validation at
the API layer to prevent mis-submission. This is rejected because it creates a single
point of failure for a critical safety property. The parameter resolution function would
have no structural ability to distinguish an asset interest rate from a liability interest
rate; the only protection would be the API layer's correct execution. An internal tool,
a migration script, or a future API path that bypassed ledger-side validation would
silently produce resolvable values with no structural signal that they had been placed
incorrectly.

**Flat enumerated key set.** Parameter keys are assigned from an explicit enumeration
(e.g. integer identifiers, or opaque strings with no derivable structure). This approach
provides strict control over the key space but sacrifices self-description entirely. A
key of `42` or `k_007` requires external context to interpret. Debuggability, audit, and
the ability to understand the key space without a full catalogue lookup are all degraded.
The benefits of this approach — tighter namespace control — are achievable with the
feature-namespaced convention at lower cost to readability.