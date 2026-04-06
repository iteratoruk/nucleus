# SPK-001: Which approach to uniform catalogue metadata is proportionate at pre-v1 catalogue size?

**Status:** Open

---

## Question

Of the two candidate approaches identified in RFP-002 (annotated feature registration and
map-based `FeatureConfiguration`), which resolves the catalogue metadata inconsistency in a
manner proportionate to a two-feature, pre-version-1 catalogue — and is either approach
worth the scope of change now, or should the inconsistency be deferred with an explicit
trigger condition?

## Motivation

Future additions to the account feature catalogue are blocked from proceeding without an
agreed resolution to this question. The catalogue architecture document identifies fixed
term and further features as anticipated; each new feature currently requires manual updates
to at minimum four places, with `toFeatureConfiguration()` capable of silently dropping a
feature from all GET responses if missed. The pre-version-1 window is the correct time to
resolve this: after version 1 is declared, Approach B (map-based `FeatureConfiguration`)
becomes a breaking API change requiring a migration strategy. The spike does not block any
currently scoped story, but resolving it now avoids imposing a structural constraint on the
next feature addition.

## Time-Box

**Initial allocation:** One day (two sessions).

**Extension approval condition:** If the investigation surfaces a third approach — one
distinct from Approach A and Approach B — that requires non-trivial prototyping to assess,
one additional session may be used. The extension requires an explicit statement of the
third approach and why it warrants prototyping before it is approved.

**Time used:** —

## Approach

1. Read the current implementation in full: `FeatureConfiguration`, `presentFeatures()`,
   `FeatureCatalogueConverter.toFeatureConfiguration()`, `featureLedgerSideApplicability`,
   `propertyConstraintViolations()`, and the existing `@BoundaryGoverned` annotation pattern
   in `AccountFeaturesService.opennessViolations()`.

2. Prototype Approach A (annotated feature registration): introduce a class-level
   `@CatalogueFeature(ledgerSides = [...])` annotation and a property-level
   `@MaxDecimalPlaces(n)` annotation; implement a catalogue registry bean that discovers
   annotated feature classes and drives `featureLedgerSideApplicability` and
   `propertyConstraintViolations()` from them; make `toFeatureConfiguration()` iterate over
   the registry rather than hardcoding names and class references. Add a stub third feature
   and a regression test that exercises it end-to-end to confirm the mechanism works without
   manual updates. Assess the prototype against the existing `AccountFeaturesApiTest` and
   `FeatureCatalogueConverterTest`.

3. Assess Approach B (map-based `FeatureConfiguration`) analytically: identify what changes
   to the API JSON shape it would require, whether those changes are acceptable before
   version 1, and whether the resulting structure is more or less legible than Approach A.
   A full prototype of Approach B is not required unless the analytical assessment is
   inconclusive.

4. Apply the proportionality test: does the complexity introduced by the chosen approach
   exceed the complexity it eliminates, given a catalogue of two features with anticipated
   growth? If the answer is yes, characterise the trigger condition at which the balance
   shifts (e.g. on addition of the third feature, or on declaration of version 1).

## Determined Output

A recommendation document saved to `docs/spikes/SPK-001-catalogue-metadata-consistency-recommendation.md`.
The recommendation must state: which approach is adopted (or that adoption is deferred),
the reasoning, and — if deferral — the trigger condition that would cause the decision to
be revisited.

If the spike concludes that Approach A is preferable and proportionate, the recommendation
is accompanied by a working prototype on a scratch branch, and a task document (TSK-NNN)
is produced to implement it.

If the spike concludes the benefit is insufficient at the current catalogue size, the
recommendation document is the deferral record: it states the trigger condition explicitly
and closes the spike without a follow-on task.

## Result

_Populated at the end of the spike._