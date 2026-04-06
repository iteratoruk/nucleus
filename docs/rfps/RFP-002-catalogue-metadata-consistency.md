# RFP-002: Catalogue metadata is partially reflection-driven and partially hardcoded

**Date:** 2026-03-25
**Status:** Proposed
**Bounded context:** Account Feature Catalogue
**Produced by:** Code review session on 2026-03-25

---

## Finding

The account feature catalogue has no uniform mechanism for expressing the structural
properties of its features. Three distinct mechanisms are in use, applied inconsistently:

**Reflection-driven (automatic):** `FeatureCatalogueConverter.toParameterValues()` uses
`features.presentFeatures()` to iterate over the features present in a
`FeatureConfiguration`, derives the feature name from the class name via `featureNameFor`,
and uses Jackson to convert non-null properties to parameter key-value pairs. Adding a
new feature to `presentFeatures()` is sufficient to make it flow through the write path
automatically.

**Partially reflection-driven:** `opennessViolations()` in `AccountFeaturesService` uses
`feature::class.memberProperties` and `property.findAnnotation<BoundaryGoverned>()` to
discover which properties are boundary-governed. The `@BoundaryGoverned` annotation is
declared on the property in the feature class. This is annotation-driven: declaring the
annotation on the property is sufficient to have it validated.

**Hardcoded:** Three constructs require manual synchronisation with any new feature:

1. `FeatureCatalogueConverter.toFeatureConfiguration()` (lines 96–106) — hardcodes
   feature name strings and class references:
   ```kotlin
   return FeatureConfiguration(
       liabilityInterest = byFeature["liabilityInterest"]?.let {
           objectMapper.convertValue(it, LiabilityInterestFeature::class.java)
       },
       assetInterest = byFeature["assetInterest"]?.let {
           objectMapper.convertValue(it, AssetInterestFeature::class.java)
       },
   )
   ```

2. `featureLedgerSideApplicability` (lines 178–182) — hardcodes the feature name to
   applicable ledger sides mapping:
   ```kotlin
   private val featureLedgerSideApplicability: Map<String, Set<LedgerSide>> =
       mapOf(
           "assetInterest" to setOf(LedgerSide.ASST),
           "liabilityInterest" to setOf(LedgerSide.LIAB),
       )
   ```

3. `propertyConstraintViolations()` (lines 184–192) — hardcodes decimal precision
   constraint checks per feature:
   ```kotlin
   private fun propertyConstraintViolations(features: FeatureConfiguration): List<NucleusViolation> =
       listOfNotNull(
           features.liabilityInterest?.let {
               sevenDecimalPlaceViolation("liabilityInterest", "interestRate", it.interestRate)
           },
           features.assetInterest?.let {
               sevenDecimalPlaceViolation("assetInterest", "interestRate", it.interestRate)
           },
       )
   ```

Additionally, `FeatureConfiguration.presentFeatures()` is itself manually maintained:
```kotlin
fun presentFeatures(): List<AccountFeature> = listOfNotNull(liabilityInterest, assetInterest)
```

The consequence: adding a third feature to the catalogue requires updates to at minimum
four places (`FeatureConfiguration`, `presentFeatures()`, `toFeatureConfiguration()`,
`featureLedgerSideApplicability`), and a fifth if the feature has BigDecimal properties
(`propertyConstraintViolations`). Only one of these (`presentFeatures()`) will produce
a test failure if forgotten; `toFeatureConfiguration()` will silently drop the feature
from all GET responses.

The `@BoundaryGoverned` annotation pattern is the most mature mechanism in the current
code. The hardcoded constructs are inconsistent with it. This is not a hypothetical
future problem: the architecture document declares that the catalogue will grow (asset
interest, liability interest, and fixed term are explicitly identified; further features
are anticipated), and the document notes the catalogue is pre-production and mutable
without backward-compatibility obligation until version 1 is declared.

## Benefit

A feature added to the catalogue is automatically included in the read path, the
ledger-side validation, and the property constraint validation without manual updates
to separate constructs. The risk of a silent omission (a new feature stored but never
included in GET responses because `toFeatureConfiguration()` was not updated) is
eliminated. The `@BoundaryGoverned` precedent suggests the codebase already accepts
annotation-driven declaration as the right model for this context.

## Proposed Approach

This finding has genuine implementation uncertainty, which is why a spike is proposed
rather than a task. The uncertainty is as follows.

`FeatureConfiguration` is a data class with named, typed properties. Constructing it
from a map of resolved parameter values requires knowing the mapping from feature name
string to property slot — something the Kotlin type system does not provide directly
without either changing the data structure or using reflection on `FeatureConfiguration`
itself.

The two most promising approaches are:

**Approach A — annotated feature registration.** Introduce a class-level annotation
on each `AccountFeature` implementation (e.g., `@CatalogueFeature(ledgerSides = [LIAB])`)
and a property-level annotation for decimal precision (e.g., `@MaxDecimalPlaces(7)`).
A catalogue registry bean discovers annotated feature classes on startup and derives
`featureLedgerSideApplicability` and `propertyConstraintViolations` from them. For
`toFeatureConfiguration()`, a catalogue registry can provide the mapping from feature
name string to `KClass<out AccountFeature>`, allowing `FeatureCatalogueConverter` to
iterate over known features and convert each using `objectMapper.convertValue`. The
`FeatureConfiguration` constructor invocation remains hardcoded but the catalogue
metadata is co-located with the feature class.

This approach eliminates items 2 and 3 from the finding and reduces item 1 (the
`toFeatureConfiguration()` loop becomes driven by the registry, not hardcoded). It does
not eliminate the need to add a property to `FeatureConfiguration` and update
`presentFeatures()` when adding a new feature.

**Approach B — map-based FeatureConfiguration.** Replace the typed `FeatureConfiguration`
data class with a map-based structure (e.g., `Map<String, AccountFeature>`). This
eliminates `toFeatureConfiguration()`'s construction problem entirely — the converter
builds the map generically. `presentFeatures()` becomes `features.values.toList()`.
However, this trades compile-time property access for runtime map lookups, loses the
typed external API contract, and changes the JSON serialisation shape of the API
response — which is a backward-compatibility concern once the catalogue reaches version 1.

The spike should determine which approach (or a third) is preferable, and whether the
benefit justifies the scope of change at this point in the catalogue's evolution.

## Scope

The spike would examine and prototype changes to:
- `FeatureConfiguration` and its `presentFeatures()` method
- `FeatureCatalogueConverter` — specifically `toFeatureConfiguration()`
- `featureLedgerSideApplicability` and `ledgerSideApplicabilityViolations()`
- `propertyConstraintViolations()` and any new annotation type(s)
- `AccountFeaturesService` — construction of `opennessViolations()` is already
  annotation-driven and should not require changes

Out of scope: the `@BoundaryGoverned` annotation and openness validation — these are
already working correctly and are the model to follow.

A task would follow the spike if the chosen approach is better and the scope is
proportionate.

## Verification Criterion

`AccountFeaturesApiTest` covers the ledger-side validation, property constraint
validation, and the full GET read path for both features. Any implementation of this
proposal must leave all existing `AccountFeaturesApiTest` scenarios passing. The
`FeatureCatalogueConverterTest` covers the conversion direction; its tests must also
pass unchanged.

If the spike produces a prototype, a regression test should be added that exercises a
third (hypothetical or stub) feature end-to-end before the main implementation, to
confirm the proposed mechanism works without manual updates.

## Risk

**Risk: complexity increase before simplification.** The annotation registry approach
introduces a new abstraction (the catalogue registry bean) before the simplification
is realised. If the catalogue grows slowly, this overhead may not be justified at the
moment of the third feature.

**Risk: Approach B breaks the external API contract.** A map-based response structure
would change the JSON shape of `GET /account-features` and `PUT /account-features`
responses, which are the primary external contracts. Any such change before version 1 is
acceptable per the architecture document, but it must be understood as a breaking change
and treated accordingly.

**Risk: reflection fragility.** Both approaches rely on class-level or property-level
reflection to drive behaviour. This is already the pattern in `opennessViolations()` and
`toParameterValues()`, so it is not novel risk — but it does mean that refactoring
feature class names would require care.

## Work Unit Classification

**Spike (SPK-NNN).** The correct approach to the `FeatureConfiguration` conversion
problem is not established. The spike should prototype at least Approach A and assess
whether the benefit is proportionate. If the spike confirms a better approach, a task
follows. If the spike finds the benefit insufficient at the current catalogue size, the
decision to defer should be recorded explicitly.

## Decision

_Pending review._