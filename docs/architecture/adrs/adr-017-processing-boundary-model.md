# ADR-017: Processing Boundary Model and Openness Categories

**Date:** 2026-03-22
**Status:** Accepted

## Context

ADR-002 established that a business date is closed when end-of-day processing
completes, that the Account Servicing context signals this by emitting `PeriodClosed`
and `PeriodReopened` events, and that Parameter Configuration maintains a set of closed
business dates and enforces the boundary at write time. That model was correct and
remains in force, but it was expressed as a single implicit mechanism applying uniformly
to all parameter value properties.

Two extensions are now required. First, different feature properties are consumed by
processing at different cadences — daily accrual, monthly statements, annual tax
calculations — and each cadence may establish its own closure boundary. A property that
feeds daily interest accrual cannot permit backdating past the last completed accrual
run, but a property that feeds only annual reporting may permit backdating within the
open annual window. A single closure mechanism cannot express this. Second, some
properties give rise to derived internal properties calculated once at account opening
and stored immutably; these require a structurally different constraint that does not
reference any processing boundary at all. These two needs motivate the three-category
openness model introduced here.

## Decision

**There are three distinct openness categories for feature properties. They are
structurally different and must not be conflated.**

**Category 1: `GLOBAL`.** The property carries no processing scope constraint.
Backdating to any effective datetime is always permitted, regardless of the closure
state of any processing boundary. `GLOBAL` is not a boundary; it is the explicit
absence of a boundary constraint. It is the appropriate category for properties whose
retrospective change carries no consistency risk relative to any finalised processing
output. It is also the default category for properties whose processing scope has not
yet been identified. The reasoning for this default is given below.

**Category 2: Named processing boundary categories.** The property is governed by a
named processing boundary. Backdating is permitted only within the open window: the
submitted effective datetime's business date must not be on or before the most recent
closure timestamp for the named boundary. The following named categories are defined:

| Category name | Processing class it protects |
|---|---|
| `BUSINESS_DAY_CLOSE` | End-of-day processing (daily interest accrual, daily ledger settlement). Signalled by the `PeriodClosed` event defined in ADR-002. |
| `WEEK_CLOSE` | Weekly processing completion. |
| `MONTH_CLOSE` | Month-end processing completion. |
| `QUARTER_CLOSE` | Quarter-end processing completion. |
| `YEAR_CLOSE` | Year-end processing completion. |
| `TAX_YEAR_CLOSE` | Tax year-end processing completion. |

Each named category has its own closure projection in Parameter Configuration: a record
of the most recent closure timestamp received for that boundary. A submission for a
boundary-governed property is valid if and only if its effective datetime's business
date is strictly after the most recent closure timestamp for its declared boundary. If
no closure has been received for a boundary, the open window is unbounded and all
effective datetimes are permitted.

The `PeriodClosed` and `PeriodReopened` events defined in ADR-002 are the boundary
lifecycle events for the `BUSINESS_DAY_CLOSE` category. Their event names, payload
structure, and idempotency guarantees are established by ADR-002 and are not changed
here. `BUSINESS_DAY_CLOSE` is the category that end-of-day processing closes.

**Category 3: `PROSPECTIVE_ONLY`.** The property may not be set with a past effective
datetime under any circumstances. The effective datetime must be strictly after the
current wall-clock time at write time. `PROSPECTIVE_ONLY` is not a processing boundary
category; it does not consult any closure projection. Its constraint is structural and
arises from the existence of derived internal properties. See ADR-018 and ADR-019.

**`GLOBAL` is the default.** A property definition that carries no explicit openness
declaration is implicitly `GLOBAL`. This is not a conservative default — it is the
safe default. An incorrectly assigned `GLOBAL` is discoverable and correctable: when
the Account Servicing context is modelled and it becomes clear that a property feeds
processing that produces finalised outputs, the category can be updated to the
appropriate named boundary. No existing operations are silently affected by that
reclassification. An incorrectly assigned boundary-governed category, by contrast,
would silently prohibit late registration operations that are legitimate — a configurer
who should be able to submit a past-effective datetime would receive a rejection with
no correct recourse. The permissive default is therefore the right default.

**Parameter Configuration maintains an independent closure projection.** For each named
processing boundary, Parameter Configuration records the most recent closure timestamp
received for that boundary. This projection is updated by consuming boundary lifecycle
events. It is not queried from the Account Servicing context or any other context. The
projection is Parameter Configuration's own durable state, updated event-by-event. The
properties of this projection follow the idempotency model established for
`PeriodClosed`/`PeriodReopened` in ADR-002 and apply to all named boundaries.

## Consequences

**Positive:**

- The ADR-002 model is preserved and extended. The `PeriodClosed` and `PeriodReopened`
  event contracts are unchanged. `BUSINESS_DAY_CLOSE` is the named category that
  directly maps to them.
- Future account servicing contexts operating on weekly, monthly, or annual cadences
  can introduce their own boundary categories without changing the enforcement
  mechanism. The category name is declared in the feature catalogue; the enforcement
  logic is uniform across all named categories.
- `GLOBAL` explicitly signals "no processing scope constraint identified" — it is
  readable as a positive declaration, not merely an absence of annotation.
- The three-category model covers the complete range of backdating semantics: always
  permitted (`GLOBAL`), permitted within the open window (named boundary), and never
  permitted (`PROSPECTIVE_ONLY`).

**Negative:**

- The catalogue now carries explicit openness declarations for most properties, rather
  than relying on a single implicit mechanism. Properties that were implicitly
  covered by the ADR-002 model must be explicitly classified as `BUSINESS_DAY_CLOSE`
  where they were previously "default."
- Each named boundary beyond `BUSINESS_DAY_CLOSE` requires a defined event production
  path. An orphaned category name — one with no production source — will never receive
  a closure event and will behave as permanently open, which may be incorrect but is
  not detectable at write time.

**Risks:**

- **Misclassification as `GLOBAL`.** If a property that should be `BUSINESS_DAY_CLOSE`
  is left as `GLOBAL` (by omission or error), backdating past a closed business day
  will be silently permitted. The risk is in the permissive direction: a configurer
  could submit a configuration change effective on a closed business day, and the
  closed-day accruals that depended on that property would be inconsistent with the
  revised configuration. This is the category misclassification risk and is addressed
  by requiring explicit classification with stated reasoning for every property in
  the catalogue.
- **Orphaned named boundary.** As described above. Mitigated by architecture review
  at catalogue definition time.

## Alternatives Considered

**`BUSINESS_DAY_CLOSE` as the default.** A property with no explicit declaration is
governed by `BUSINESS_DAY_CLOSE`. This was considered and rejected. An incorrect
`BUSINESS_DAY_CLOSE` assignment silently prohibits legitimate backdating operations —
a configurer who should be able to register configuration late receives a rejection
with no valid path to correction. An incorrect `GLOBAL` assignment permits operations
that may be wrong, but this is discoverable when the processing model is defined.
Conservatism in the prohibitive direction is not safer than conservatism in the
permissive direction when the prohibitive direction silently removes a capability that
the domain requires.

**Single boundary mechanism (ADR-002 model, unchanged).** Retaining end-of-day
processing as the only boundary, applying to all properties. Rejected because it cannot
express the distinction between a property that feeds daily accrual (which genuinely
requires `BUSINESS_DAY_CLOSE` semantics) and a property that is safe to backdate
indefinitely (`GLOBAL`), or a property that must never be backdated
(`PROSPECTIVE_ONLY`).

**Wall-clock closure timestamps.** Recording the wall-clock time at which processing
completed rather than the business date closed. Rejected for the reasons established
in ADR-002: if end-of-day processing for date D completes at 02:00 on D+1, a
wall-clock timestamp introduces ambiguity for effective datetimes on D+1 before
02:00. Business date granularity makes the closed window unambiguous regardless of
when processing completes.