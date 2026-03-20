# Domain Model: Account Feature Catalogue

## Bounded Context

**Account Feature Catalogue** is responsible for defining the strongly-typed,
externally-visible representation of configurable account behaviour in Nucleus. It owns
the set of named account features, their properties, their value types, their
ledger-side applicability, and the convention by which they map to internal parameter
keys in the Parameter Configuration context. It also owns the API boundary layer that
translates between the external feature representation and the internal parameter
key-value model.

This context does not own parameter value storage or resolution logic — those are the
responsibility of the Parameter Configuration context. It does not own the interpretation
of feature values for account servicing behaviour — that is the Account Servicing
context's concern. What this context owns is the contract: the external representation
that clients submit, the validation rules for that representation, and the translation
that converts it to and from the internal key-value model.

What this context explicitly does not own: the semantics of any individual feature (what
"enabled" on an interest feature causes the Account Servicing context to do), the
schedule or mechanism by which feature values are applied to accounts, the lifecycle of
accounts against which feature values are resolved, and the validity of feature values
in commercial or regulatory terms (e.g. whether an interest rate is commercially
reasonable).

---

## Ubiquitous Language

**Account feature.** A named, typed configuration capability that governs an aspect of
account behaviour. Each feature has a defined set of properties. A feature is enabled or
disabled at any given node and point in time; enabling a feature in the catalogue makes
its values available for resolution — it does not directly trigger servicing behaviour.

**Feature name.** The canonical external identifier of a feature within the catalogue,
used as the key in the external API representation. Feature names are camelCase strings
(e.g. `assetInterest`, `liabilityInterest`). A feature name also serves as the namespace
prefix for all internal parameter keys derived from that feature's properties.

**Feature property.** A named, typed attribute of a feature. Each property resolves
independently via the standard parameter value hierarchy walk. A property may be set
without setting other properties of the same feature; this is the normal case, not an
exception.

**Property name.** The camelCase name of a property within a feature (e.g. `enabled`,
`interestRate`). Together with the feature name, it uniquely identifies a property across
the entire catalogue.

**Parameter key.** The internal key used to store and resolve a feature property value
in the parameter value hierarchy. Derived from the feature name and property name by the
convention `{featureName}.{propertyName}`. The parameter key is internal to Nucleus.
Clients never see it — they submit feature names and property names; the boundary layer
derives the parameter keys.

**Ledger side applicability.** The set of ledger sides for which a feature is valid,
declared in the catalogue definition for each feature. The account-features API enforces
this constraint at submission time using the ledger side of the submitted classification
code (its first segment). A submission that includes a feature not applicable to the
classification code's ledger side is rejected with a structured, actionable error.

**Feature catalogue.** The complete, versioned set of account feature definitions: their
names, properties, value types, applicable ledger sides, and derived parameter keys. The
catalogue is the authoritative source for all of these. The internal parameter key space
is not an independent contract — it is derived from the catalogue and changes if the
catalogue changes.

**Catalogue version.** A declared state of the feature catalogue. Prior to version 1,
feature and property definitions may be mutated without backward compatibility obligation.
Once version 1 is declared and the catalogue enters production use, it becomes a critical
boundary contract: it may only be extended (new features, new properties on existing
features), never reduced or modified in ways that break existing submissions or responses.
The versioning mechanism is outside the scope of this document.

**Interest rate.** A gross annual rate expressed as a decimal fraction to seven decimal
places (e.g. `0.0350000` for 3.5%). The rate is stored and resolved exactly as provided;
no implicit conversion to or from percentage notation occurs within Nucleus. HALF_EVEN
rounding applies at seven decimal places.

---

## Structural Convention: Feature to Parameter Key Mapping

The mapping from account feature properties to internal parameter keys follows a single
declared convention:

> **Parameter key = `{featureName}.{propertyName}`**

Both components are camelCase. The separator is a single period. No other separator is
used within either component — feature names and property names must not themselves
contain periods, because the mapping would then become ambiguous when splitting a
parameter key to recover the feature and property identity.

This constraint is enforced at catalogue definition time, not at submission time. A
feature or property definition that violates it is malformed.

**The mapping is mechanical but declared.** The parameter key for any feature property
can be derived algorithmically from the feature and property names without consulting a
registry. However, the catalogue definition for each feature explicitly states its
derived parameter keys so that the mapping is verifiable directly, not only through
derivation. As the catalogue grows and feature names become more numerous, mechanical
derivation without a declared reference is a source of drift and silent inconsistency.

**The feature name is the namespace.** A key of the form `assetInterest.interestRate`
cannot be mistaken for a property of any feature whose name is not `assetInterest`. This
is structural: the resolution function — which knows nothing of the feature catalogue —
operates on the key as an opaque string. It will never return a value stored under
`assetInterest.interestRate` in response to a query for `liabilityInterest.interestRate`.
No hierarchy walk, no node configuration, and no account attachment can cause one to
shadow the other. See the Resolution Safety section below.

---

## Resolution Safety

Two independent structural constraints together guarantee that a feature property value
set for one feature cannot be resolved as a property of a different feature, or as a
property applicable to the wrong ledger side.

**First constraint — ledger-side enforcement at submission time.** The account-features
API determines the ledger side of the submitted classification code from its first
segment, and rejects any feature in the submission that is not declared applicable to
that ledger side. A configurer cannot write a `liabilityInterest` configuration to a
`LEND` node; the API will reject it before any write reaches the parameter value
hierarchy. This constraint operates at the API layer — it is a guard at the point of
entry.

**Second constraint — feature-namespaced parameter keys.** Because every feature
property resolves under a key prefixed with the feature name, there is no key shared
between two features. The resolution function is catalogue-agnostic: it does not need to
know that `assetInterest.interestRate` is an interest rate, or that it belongs to a
feature applicable only to asset accounts. The key itself encodes this information
structurally. A consuming context that queries for `assetInterest.interestRate` will
never receive a value that was submitted as `liabilityInterest.interestRate`, regardless
of what the node tree contains.

**Why both constraints are necessary.** The first constraint protects against writing
the wrong feature to the wrong node. The second constraint protects against resolving a
correctly stored value as the wrong feature. If only the first constraint existed, a bug
or bypass in the API validation layer — or a direct write via an internal tool — could
result in a value stored under a shared key being resolvable on accounts of the wrong
type. The second constraint makes this impossible structurally, independent of whether
the API layer functioned correctly. In a regulated financial system, a single point of
failure for a constraint of this consequence is not acceptable.

This is the rationale for ADR-008: the feature-namespaced key convention is not a
naming preference — it is a load-bearing safety property. See ADR-008.

---

## Feature Definitions

### Asset Interest

**Feature name:** `assetInterest`

**Applicable ledger sides:** asset

**Description.** Governs interest behaviour on asset accounts (lending products, where
the outstanding balance is an asset to Nucleus and a liability to the account holder).
The feature defines that interest is applicable and at what rate — it does not define
how interest is accrued, compounded, or posted. Compounding strategy, accrual basis, and
posting direction are properties that will be added to this feature in a future
architecture session when interest behaviour is implemented. The parameter key namespace
`assetInterest.*` is reserved for these future properties; their definitions must not be
assumed from this document.

**Properties:**

| Property name | Parameter key | Type | Precision | Notes |
|---|---|---|---|---|
| `enabled` | `assetInterest.enabled` | boolean | — | Whether asset interest is operative on the account. A resolved absent value is treated as disabled by the consuming context. |
| `interestRate` | `assetInterest.interestRate` | decimal | 7 d.p. | Gross annual interest rate as a decimal fraction. `0.0350000` represents 3.5%. Must be non-negative. No upper bound is enforced in this version. |

**Note on `enabled`.** `enabled` is a property of the `assetInterest` feature, not a
shared mechanism across features. The parameter key `assetInterest.enabled` is distinct
from any other feature's enabled key. Setting `assetInterest.enabled` at a node has no
effect on any other feature's enabled state at that node or any other node.

---

### Liability Interest

**Feature name:** `liabilityInterest`

**Applicable ledger sides:** liability

**Description.** Governs interest behaviour on liability accounts (savings products,
mortgages, where the balance is a liability to Nucleus and an asset to the account
holder). As with asset interest, compounding and accrual properties are outside the scope
of this session. The namespace `liabilityInterest.*` is reserved.

**Properties:**

| Property name | Parameter key | Type | Precision | Notes |
|---|---|---|---|---|
| `enabled` | `liabilityInterest.enabled` | boolean | — | Whether liability interest is operative on the account. A resolved absent value is treated as disabled by the consuming context. |
| `interestRate` | `liabilityInterest.interestRate` | decimal | 7 d.p. | Gross annual interest rate as a decimal fraction. `0.0350000` represents 3.5%. Must be non-negative. No upper bound is enforced in this version. |

---

## API Contract

### `PUT /account-features/{classificationCode}`

Registers or updates account feature configuration for a classification code. This is
the primary write operation used by all configurer personas.

**Request body:**

```json
{
  "effectiveDate": "2026-04-01",
  "features": {
    "liabilityInterest": {
      "enabled": true,
      "interestRate": "0.0350000"
    }
  }
}
```

The `effectiveDate` field specifies the date from which all property values in this
submission become effective for resolution purposes. It is not the date on which the
submission is made. If omitted, the current date is used as the default — this is an
explicit input substitution, not an implicit dependency on system time, and follows the
same convention as the resolution date default in the parameter value hierarchy. The
effective date applies uniformly to all properties in the submission. Properties that
require different effective dates must be submitted separately. See ADR-009.

The `features` object contains the feature configuration. Only the features and
properties included in `features` are written. A feature omitted from `features` is not
touched — no writes are issued for it, and its existing parameter values are not
affected. A property omitted from a feature present in `features` is likewise not
touched. This is consistent with the model that partial configuration is the normal case:
a configurer setting an interest rate does not need to re-submit the `enabled` flag, and
must not be required to do so.

**Validation sequence:**

1. The classification code is validated for structural correctness (segment format,
   length constraints) per the parameter value hierarchy domain model.
2. The ledger side is determined from the first segment of the classification code.
3. Each feature name in `features` is validated against the catalogue. Unknown feature
   names are rejected with a structured error.
4. Each feature's applicability to the determined ledger side is checked. Features not
   applicable to the ledger side are rejected with a structured error identifying the
   feature name and the ledger side mismatch.
5. Each property value is validated: type correctness, format, and any declared value
   constraints (e.g. non-negative for `interestRate`).
6. Validation is exhaustive: all errors across all features and all properties are
   collected and returned together. A single error does not short-circuit validation of
   the remaining submission. This follows the constraint established in the configurer
   personas that invalid configuration must be rejected with structured, actionable
   errors.
7. If validation passes, all property values are written as parameter values at the
   classification node with the supplied effective date. Each property is written
   independently as a separate parameter value keyed under its derived parameter key.
   All writes are subject to the closed period constraint established in the parameter
   value hierarchy model.

**Idempotency.** Submitting identical feature configuration for the same classification
code and effective date is safe. A repeated submission for an existing (classification
code, parameter key, effective date) triple is a supersession write — the prior value is
retained in the write audit trail and the new value becomes active. If the value is
unchanged from the prior write, the result is the same active value; a supersession write
is still made and recorded.

**Response.** The resolved feature configuration for the classification code as of the
submitted effective date, in the same strongly-typed feature representation used by the
`GET` endpoint. The resolution traversal begins at the submitted classification node and
walks up to the global node; account-level values are not included. This allows the
caller to confirm the effective state of the node after writing, including values
inherited from ancestor nodes that were not part of the current submission.

---

### `PATCH /account-features/{classificationCode}`

Partial update. Request body structure and write semantics are identical to `PUT`. The
distinction between PUT and PATCH write semantics for omitted properties is resolved:
both verbs follow the property-level partial write behaviour described above — omitted
properties are unchanged. In the current model, PUT and PATCH are semantically
equivalent on the write path. The value of maintaining both verbs is a question to be
resolved once the full write semantics are stable; for now, both are supported and both
behave identically.

---

### `GET /account-features/{classificationCode}?asAt={date}`

Returns the resolved account feature configuration for a hypothetical account at the
given classification code and effective date. Behaviour, semantics, and risk profile are
defined in ADR-007. The response format is the same as the PUT response: a strongly-typed
feature representation with resolved values for all configured features and properties,
with internal parameter keys translated back to feature and property names.

Properties for which the resolution walk returns no value are omitted from the response.
Properties for which the resolution walk returns an explicit absence marker are
represented distinctly from absent properties — see OQ-1.

Internal Nucleus features — those written to the parameter hierarchy by Nucleus itself
rather than by external clients — are not included in the response. The response
represents only features defined in the external catalogue.

---

## Context Relationships

**Parameter Configuration context (downstream store):**
The account-features API boundary layer translates feature property submissions into
parameter key-value writes directed at the Parameter Configuration context. It translates
`GET` responses from parameter key-value pairs back into the typed feature representation.
The feature catalogue defines the external contract; the Parameter Configuration context
stores and resolves the values. The integration pattern is customer/supplier: the Feature
Catalogue context is the upstream supplier of writes; the Parameter Configuration context
is the downstream store. The Parameter Configuration context has no knowledge of the
feature catalogue — it stores and resolves keys as opaque strings.

**Account Servicing context (downstream consumer of resolved values):**
The Account Servicing context resolves parameter values by key — using keys derived from
the feature catalogue convention — and interprets them to govern account behaviour.
It queries the resolution function with keys such as `assetInterest.interestRate` and
receives the applicable value. It does not interact with the feature catalogue directly;
the catalogue convention ensures the keys it uses are consistent with what configurers
submit, but this consistency is enforced by the shared naming convention at definition
time, not by a runtime contract.

**Configurer personas (Cameron, Sasha, Liam, Maya — upstream clients):**
Configurers interact exclusively with the external feature representation. They submit
feature names and property values; they never see parameter keys. The feature catalogue's
stability obligation — backward compatibility after version 1 — is an obligation to
these clients. The catalogue must not change in ways that invalidate previously submitted
configurations or previously consumed responses.

---

## Open Questions

**OQ-1: Explicit absence in the API representation. ADR-010 candidate.**

The parameter value hierarchy model defines explicit absence as a sentinelled parameter
value that terminates the resolution walk (ADR-006). The account-features API must define
how this concept is exposed across both the read and write paths.

On the read path (`GET /account-features`): a property for which the resolution walk
encounters an explicit absence marker is a different state from a property for which the
walk finds no value at all. ADR-007 notes this risk explicitly. The response representation
must distinguish these two states. The mechanism — an envelope flag, a distinct sentinel
value in the property position, or some other approach — is not yet defined.

On the write path: how does a client deliberately set explicit absence for a property —
signalling "at this node, this property has no value and inheritance from parent nodes is
suppressed"? Options include submitting the property with a `null` value, submitting a
declared sentinel string, or a `DELETE` operation on the property. Each has implications
for API ergonomics, for the meaning of `null` in JSON submissions, and for backwards
compatibility once the catalogue is in production use.

This decision is deferred because the initial TDD stories do not require explicit absence
writes. The feature definitions in this document do not expose explicit absence as a
first-class client-visible concept in the initial implementation. This must be resolved
before any story that requires a client to suppress inheritance is implemented.

**OQ-2: Ledger side classification governance. ADR-011 candidate.**

The API enforces ledger-side applicability of features at submission time. This requires
the system to know, for each possible first classification code segment, whether it
designates an asset side or a liability side. How this is declared, stored, and extended
is not yet defined.

For the initial implementation, a declared enumeration within Nucleus is sufficient: the
system knows that `LEND` is the asset side and `SAVE` and `MORT` are liability sides.
But the governance question — whether this enumeration is extensible by external clients,
whether new ledger side designations require a Nucleus deployment, and whether the
classification code structure itself is the appropriate mechanism for encoding ledger
side — carries long-term architectural consequence and should be recorded explicitly
before the node tree grows beyond the initial three root nodes.

This question does not block the initial TDD implementation, which operates against
known classification code prefixes. It must be resolved before the system is expected to
support a fourth root node or before the ledger-side designation logic is tested as a
standalone concern.

---

## ADR Candidates Summary

| Candidate | Decision to be recorded |
|---|---|
| ADR-008 | Feature-namespaced parameter key convention: `{featureName}.{propertyName}` as the internal key structure, and the two-constraint safety model (ledger-side enforcement at submission plus key-level namespace segregation) that makes cross-feature mis-resolution structurally impossible. |
| ADR-009 | Effective date granularity in the account-features API: a single effective date per submission applies to all properties in the request, and its resolution to the current date when absent is an explicit substitution, not an implicit system clock dependency. |
| ADR-010 | Explicit absence in the account-features API representation: how deliberate absence (an explicit absence marker in the parameter hierarchy) is distinguished from non-configuration in both the read and write paths, and what mechanism clients use to set explicit absence. |
| ADR-011 | Ledger side classification governance: how the system knows which first classification code segments designate asset sides and which designate liability sides, whether this is extensible without a Nucleus deployment, and whether the classification code structure is the right long-term mechanism for encoding ledger side. |