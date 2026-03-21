# ADR-011: Ledger Side as a Closed Two-Value Enumeration

**Date:** 2026-03-21
**Status:** Accepted

## Context

The parameter value hierarchy model defines the ledger side as the first segment of a
classification code — the one element of a classification code from which Nucleus
explicitly infers semantics. The account-features API uses the ledger side to determine
which catalogue features are valid for a given submission, rejecting features not
applicable to the ledger side of the submitted classification code.

The architecture documents and user stories were initially written using illustrative
classification code examples drawn from banking product types: `LEND` (lending), `SAVE`
(savings), `MORT` (mortgage). These examples implied that the ledger side might be an
open or product-oriented label — one that could grow as new product families were
introduced. The question of how the ledger side is declared, stored, and extended was
recorded as OQ-2 in the Account Feature Catalogue domain model and listed as an ADR
candidate.

During the implementation of NUC-001, the ledger side was expressed in code as a typed
enumeration with two values. The decision was made implicitly in the course of
implementation; this ADR records it explicitly and resolves OQ-2.

The fundamental question is whether the ledger side is a product taxonomy label (which
could grow arbitrarily) or an expression of a structural financial property (which is
determined by the nature of the relationship between Nucleus and the account holder).
These two framings lead to different designs.

## Decision

The ledger side is a closed, two-value enumeration: `ASST` (asset) and `LIAB`
(liability). Every classification code's first segment must be one of these two values.
No other value is valid as a ledger side. The enumeration is internal to Nucleus; it is
not extensible by external clients or by configuration. Adding a third ledger side value
requires a Nucleus deployment.

The two values are not product labels. They express the direction of the financial
relationship between Nucleus and the account holder: `ASST` identifies accounts where
the outstanding balance is an asset to Nucleus (the account holder owes Nucleus), `LIAB`
identifies accounts where the outstanding balance is a liability to Nucleus (Nucleus owes
the account holder). This is a structural property of a double-entry accounting system,
not a product taxonomy. There are exactly two sides to a ledger entry.

The prior illustrative examples — `LEND`, `SAVE`, `MORT` — are not ledger sides under
this model. `LEND` is a product family that produces asset-side accounts; `SAVE` and
`MORT` are product families that produce liability-side accounts. Product family
differentiation, if needed, belongs at a deeper segment of the classification code, not
in the first segment. A lending product is `ASST_LEND_...`; a savings product is
`LIAB_SAVE_...`.

## Consequences

**Positive:**

- The ledger side is structurally grounded in the double-entry accounting model rather
  than in product taxonomy. It cannot be ambiguous: every balance is either an asset or
  a liability to Nucleus. The two-value enumeration reflects this binary reality.
- Ledger-side applicability enforcement in the account-features API becomes a simple
  enum membership check. There is no need for a configurable registry of valid ledger
  side values, no risk that a new product label is introduced without a corresponding
  applicability declaration in the feature catalogue.
- Feature catalogue definitions are stable. A feature declared applicable to `ASST`
  accounts is applicable to all lending products, regardless of how many lending product
  families are introduced below the `ASST` root. The catalogue does not need to be
  updated when a new product family is introduced.
- The parameter namespace is unambiguous. An `ASST_*` node can never be confused with a
  `LIAB_*` node; the ledger side is encoded in the classification code itself, structurally.

**Negative:**

- All existing architecture documents and user stories that use `LEND`, `SAVE`, or `MORT`
  as first segments of classification codes are now incorrect. These must be updated to
  use `ASST` and `LIAB` as the first segment, with product family labels moved to the
  second segment where applicable. This is a documentation debt incurred by the
  decision being made in implementation rather than in an architecture session.
- Third-ledger-side scenarios (e.g. off-balance-sheet items, memorandum accounts) cannot
  be accommodated without a Nucleus deployment. If Nucleus is ever required to support
  account types that do not map cleanly to asset or liability, this decision creates
  rework. The constraint is accepted as appropriate for a core banking system where the
  double-entry model is fundamental.

**Risks:**

- **Stale documentation.** The illustrative values `LEND`, `SAVE`, `MORT` appear in both
  architecture documents and in user story scenarios. If these are not updated promptly,
  contributors will form an incorrect mental model of the classification code structure.
  The architecture documents must be updated as a consequence of this ADR.
- **Scenario values in existing stories.** NUC-001's scenarios use `SAVE` and `SAVE_INAS`
  as classification codes. These values are now incorrect at the first segment. Future
  stories must use `ASST` and `LIAB` prefixes. Existing stories should be updated when
  they next move through the implementation cycle.

## Alternatives Considered

**An open or configurable enumeration of ledger side labels.** The ledger side values
could be stored in a configurable registry, allowing new values to be introduced without
a deployment. Rejected because it introduces a governance mechanism (who can add a new
ledger side? under what conditions?) for a concern that is structurally binary. There are
two sides to a ledger. A configurable enumeration would imply the possibility of a third,
which would be a category error in the double-entry accounting model.

**Product family labels as the first segment.** The first segment could encode the
product family (`LEND`, `SAVE`, `MORT`) and the ledger side could be inferred from a
separate mapping. Rejected because it conflates product taxonomy with financial structure.
Product families may grow, split, or be reorganised; the ledger side of a double-entry
system does not. Encoding product family in the first segment would require the feature
catalogue applicability logic to maintain a mapping from product labels to ledger sides,
creating a dependency between the catalogue and the product taxonomy that does not exist
in the domain.