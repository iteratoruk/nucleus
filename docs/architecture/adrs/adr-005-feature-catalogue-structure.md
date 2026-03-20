# ADR-005: Feature Catalogue Structure

**Date:** 2026-03-20
**Status:** Accepted

## Context

The account-features API presents a strongly-typed external view of account configuration.
Configurers (Cameron, Sasha, Liam, Maya) submit configuration against a classification
code using named account features — discrete, validated entries that correspond to
configurable behaviours Nucleus supports. Nucleus maps these entries internally to
parameter key-value pairs on the relevant node; the underlying parameter system is not
exposed to clients.

The feature catalogue is the set of named, validated account features that Nucleus
supports. A feature present in the catalogue is one Nucleus can honour; a feature absent
from the catalogue is one Nucleus does not support. The catalogue governs what can be
submitted, what validation rules apply, and what error messages a configurer receives when
a submission is invalid.

The question this ADR resolves is the structure of that catalogue: whether it is a single
unified catalogue or a set of per-ledger-side catalogues, and how the system determines
which features are valid for a given submission.

The question arises because some features are meaningful for both asset and liability
accounts — restrictions, operational flags, general behavioural parameters — while others
are meaningful only for one side. An interest feature for a lending account (asset side)
and an interest feature for a savings account (liability side) are not the same domain
concept: the direction of the calculation, the accrual basis options, the capitalisation
mechanism, and the relationship between the rate and an external reference rate differ
materially. Treating them as a single polymorphic feature, or as two features in separate
per-side catalogues, carries different consequences for catalogue maintenance, configurer
experience, and validation implementation.

Additionally, the general principle established in the domain model is that Nucleus does
not infer semantics from classification code elements — the code is a key, not a domain
object. The ledger side is the one deliberate exception to this principle.

## Decision

The feature catalogue is unified: there is a single catalogue, not separate catalogues
per ledger side.

Within that unified catalogue, features whose domain meaning genuinely differs between
asset and liability accounts are expressed as distinct catalogue entries, named to reflect
that distinction. An asset interest feature and a liability interest feature are separate
entries. They may share structural similarities but are defined independently, evolve
independently, and are validated independently. Features whose domain meaning is uniform
across ledger sides — restriction types, operational flags, general behavioural parameters
— appear as single catalogue entries applicable to both sides.

The account-features API uses the ledger-side element of the submitted classification code
to determine which catalogue features are valid for the submission. The ledger side is the
one element of the classification code from which Nucleus infers semantics; this inference
is limited to feature applicability at submission time. A submission that includes a
feature not applicable to the ledger side of the supplied classification code is rejected
with a structured error. No additional per-feature applicability metadata layer is
required: a feature's scope is expressed through its name and position in the catalogue,
and enforced through the ledger-side prefix of the classification code at submission time.

The discipline for new features added to the catalogue is: evaluate whether the feature
represents one domain concept or two. If the domain concept genuinely differs by ledger
side — in definition, in valid values, in invariants, in what downstream processing it
governs — the feature should be expressed as two named entries. If the domain concept is
uniform, it should be expressed as one. This evaluation must be recorded in the catalogue
definition, not left implicit in the feature name alone.

## Consequences

**Positive:**

- Features that are uniform across ledger sides appear once in the catalogue. There is
  no duplication of shared concepts, no risk of shared features diverging between
  per-side catalogues, and no requirement to make a change in two places when a shared
  feature evolves.
- Side-specific features reflect the domain accurately. Naming asset interest and
  liability interest as distinct catalogue entries makes the catalogue self-documenting:
  the name communicates scope. A configurer reading the catalogue knows which features
  are relevant to their product domain without consulting applicability metadata.
- Side-specific features evolve independently. A parameter added to the asset interest
  feature to support tracker rate margins has no effect on the liability interest feature.
  The two features are not coupled through a shared definition that must accommodate
  both sides.
- Validation is structurally simple. The ledger-side prefix of the classification code
  determines which features are applicable. No metadata lookup is required; the rule is
  derivable from the feature name and the code.
- Configurer experience is coherent. Sasha submitting configuration for a `SAVE` node
  encounters the liability interest feature. Maya submitting for a `MORT` node encounters
  the asset interest feature. Neither encounters features that are named neutrally but
  silently inapplicable to their product domain.

**Negative:**

- The catalogue contains two entries for every feature that differs by ledger side. For
  features that are structurally similar but domain-distinct, this means parallel
  definitions that must be maintained in coordination — not because they share behaviour
  but because they share structure. Structural updates (a new field type added to both)
  must be applied to each entry separately.
- Catalogue governance requires an ongoing decision for each new feature: one entry or
  two? This decision must be made correctly and consistently. A feature that should be
  two entries but is expressed as one imposes polymorphism on a definition that cannot
  accommodate it cleanly; a feature that should be one entry but is split unnecessarily
  inflates the catalogue and creates false symmetry.
- When a feature starts as a unified entry and later the two sides need to diverge, the
  catalogue must be split: the unified entry is replaced by two side-specific entries,
  and existing configuration that references the unified entry must be migrated. This
  migration is a versioning event with operational implications for all configurers who
  have submitted configuration using the unified feature name.

**Risks:**

- **Premature unification.** A feature that appears similar across ledger sides may be
  unified at catalogue inception because the difference is not yet visible. As the
  system matures and the two sides' requirements diverge — as they routinely do in
  financial products — the unified feature accumulates conditionally applicable fields,
  side-specific validation rules, and branching downstream behaviour. It becomes a
  polymorphic feature definition that tries to serve two masters. The split, when it
  comes, is disruptive. The mitigation is the evaluation discipline described in the
  Decision: any feature whose definition requires the phrase "if asset side, then X;
  if liability side, then Y" is a candidate for splitting.
- **Naming inconsistency.** The enforcement rule relies on feature names communicating
  scope. A feature named ambiguously — one that does not clearly indicate whether it
  applies to assets, liabilities, or both — undermines the self-documenting nature of
  the catalogue and may require metadata annotation to clarify what the name should
  express. Catalogue naming standards must be established and applied consistently.
- **Validation scope creep.** The ledger-side prefix is the one element of the
  classification code from which Nucleus infers semantics. If future requirements create
  pressure to enforce feature applicability based on other code elements (e.g., a feature
  valid only for a specific product family within a ledger side), the structural
  enforcement rule breaks down and a metadata layer becomes necessary. The catalogue
  structure should be revisited if this pressure arises rather than extending the
  structural rule beyond the ledger side.

## Alternatives Considered

**Per-ledger-side catalogues.** Two separate catalogues, one for asset accounts and one
for liability accounts. Validation selects the catalogue based on the ledger-side prefix
and validates the submission against it entirely. This is the simplest validation
implementation: no cross-catalogue reasoning is required. It is rejected because features
that are uniform across ledger sides — restrictions, operational flags — must either be
duplicated in both catalogues (creating divergence risk and a dual-maintenance burden) or
extracted into a third shared catalogue (creating a three-catalogue system with its own
routing logic). The unified catalogue handles shared features naturally and without
duplication. The per-side approach solves the applicability problem at the cost of
creating a duplication problem.

**Unified catalogue with explicit per-feature applicability metadata.** A single catalogue
where each feature entry carries an annotation declaring which ledger sides it applies to
(e.g., `applicableTo: [ASSET]`, `applicableTo: [LIABILITY]`, `applicableTo: [ASSET,
LIABILITY]`). Validation queries this annotation for each submitted feature. The
distinction from the chosen approach is that the feature may be named without reference
to its ledger-side scope, with applicability declared separately in metadata. This is
more flexible — a feature's applicability can be adjusted without renaming it — but less
self-documenting. A feature named "interestFeature" with metadata `applicableTo: [ASSET]`
requires a metadata lookup to discover its scope; a feature named "assetInterestFeature"
communicates its scope in its name. The metadata approach also creates a maintenance
dependency between the feature definition and its metadata: a feature that is redefined
to behave differently on one side without updating its metadata is an invisible
inconsistency. Where names can carry the information clearly, they should; metadata
should not duplicate what naming expresses. Rejected in favour of naming convention over
metadata annotation.