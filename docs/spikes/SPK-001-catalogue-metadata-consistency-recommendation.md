# SPK-001 Recommendation: Catalogue Metadata Consistency

**Status:** Complete
**Date:** 2026-04-06

---

## Recommendation

**Defer.** None of Approaches A, B, or C is proportionate to the current two-feature
catalogue. The resolution should be deferred with an explicit trigger condition: implement
Approach A (with Approach C annotation substitution) as the first step of the task that
adds the third catalogue feature.

---

## What was found

### Phase 1: Implementation audit

The RFP-002 description matches the live code exactly. The finding is confirmed:

- `FeatureCatalogueConverter.toFeatureConfiguration()` hardcodes two feature name strings
  and two class references. A new feature added to `presentFeatures()` without a
  corresponding update here will be silently absent from all GET responses.
- `featureLedgerSideApplicability` is a hardcoded private val mapping feature names to
  applicable ledger sides.
- `propertyConstraintViolations()` is a hardcoded `listOfNotNull` with per-feature,
  per-property calls to `sevenDecimalPlaceViolation`.
- `opennessViolations()` is already annotation-driven via `@BoundaryGoverned` and does not
  require any changes under either approach.
- `toParameterValues()` is already reflection-driven via `presentFeatures()` and Jackson.

The inconsistency is real. Three constructs require manual per-feature updates; two do not.

### Phase 2: Approach A prototype

A working prototype was implemented and confirmed passing — all 26 tests pass (25 existing
plus one new regression test exercising a stub third feature end-to-end through the read
path).

**What the prototype demonstrates:**

1. `@CatalogueFeature(ledgerSides = [...])` on each feature class drives
   `featureLedgerSideApplicability` via a `FeatureCatalogueRegistry` bean. The hardcoded
   map is eliminated.

2. `@MaxDecimalPlaces(n)` on feature properties drives `propertyConstraintViolations()`
   via reflection. The hardcoded `listOfNotNull` is eliminated.

3. `toFeatureConfiguration()` iterates the registry and uses
   `FeatureConfiguration::class.primaryConstructor!!.callBy(args)` to construct the
   response object. The hardcoded feature name strings and class references are eliminated.
   The explicit constructor call is replaced with reflection.

4. `presentFeatures()` is **not** driven by the registry. It remains manually maintained.
   Making it registry-driven would require reflection on `FeatureConfiguration` property
   values — adding another reflection call in a further direction. The prototype was kept
   to the stated scope.

5. The `FeatureCatalogueRegistry` uses an explicit list of feature classes. No classpath
   scanning is required; no new external dependencies are introduced. The explicit list
   moves the per-feature registration from 4-5 dispersed places to 1 place, but does not
   eliminate it.

**Net production code change (excluding evaluation artifacts):**
Approximately +32 lines net. Two new annotation types (~10 lines), one new registry bean
(~18 lines), modified `toFeatureConfiguration()` (reflection-based, comparable length
to current but more complex), modified `propertyConstraintViolations()` (+3 lines net),
deleted `featureLedgerSideApplicability` (-5 lines), minor changes to function signatures
and call sites.

**No new external dependencies.** `kotlin.reflect.full.primaryConstructor` and
`kotlin.reflect.KProperty1` are from the Kotlin standard library reflection module
already on the classpath.

### Phase 3: Approach B — analytical assessment

Approach B replaces `FeatureConfiguration` with `Map<String, AccountFeature>`.

The JSON wire format would be preserved — `FeatureConfiguration` already serializes
to the same JSON shape as a map would produce. There is no breaking API change before
version 1 on that count.

The deserialization problem is not preserved. `AccountFeature` is an interface. Jackson
cannot deserialize a PUT request body into `Map<String, AccountFeature>` without either
`@JsonTypeInfo` / `@JsonSubTypes` (which adds a type discriminator to the wire format,
changing the shape) or a custom deserializer that contains the feature name to type
mapping — i.e., a registry in another form. The FeatureConfiguration constructor problem
is replaced with a Jackson polymorphism problem of equivalent or greater complexity.

Type safety is degraded: typed property access (`response.features.liabilityInterest`)
becomes map lookup with unchecked cast. Legibility falls throughout. Internal consumers
of the typed representation require updates.

Approach B is not recommended at any catalogue size. It does not simplify the core problem
and introduces new ones.

### Phase 4: Proportionality assessment

**At 2 features:** The Approach A abstraction costs ~32 net production lines and eliminates
~20 lines of hardcoded per-feature maintenance. The net is approximately +12 lines — the
abstraction is underwater. The benefit does not yet justify the complexity.

**Per-feature maintenance savings from Approach A:** Adding a third feature (with a
BigDecimal property) without Approach A requires approximately 7 lines across 4-5 places.
With Approach A: approximately 3 lines across 2 places (FeatureConfiguration property,
registry list). Savings per additional feature: ~4 lines across ~3 fewer touch points.
Break-even on line count alone is around the eighth feature.

**The silent drop risk:** `toFeatureConfiguration()` silently dropping a new feature from
GET responses is a real risk, but it is mitigated by TDD discipline: a failing GET test
for the new feature must be written before implementation proceeds, and it will fail
precisely because `toFeatureConfiguration()` does not yet include the new feature. Under
strict TDD, the developer is guided to make the update. The risk is load-bearing only
when TDD discipline fails. In a codebase where TDD is an adopted practice, this is a
weak justification for structural complexity at 2 features.

**Where the balance shifts:** The risk of a missed update becomes more credible when a
developer first encounters the pattern at feature 3. At that point, the rule "update four
places" is less obvious than at feature 2, and the cost of getting it wrong — a feature
silently absent from GET responses — is greater than at feature 2 when it would be caught
immediately. The trigger is the addition of the third catalogue feature, not a vague
future threshold.

### Approach C: JSR 303 / Hibernate Validator — feasibility assessment

`spring-boot-starter-validation` (Hibernate Validator, the JSR 303 reference implementation)
is already on the classpath. No new dependency is required. This is not a hypothetical
addition; it is already present. JSR 303 is not yet used anywhere in the codebase —
this would be its first use, setting a precedent for the validation architecture.

**What JSR 303 addresses in the current finding:**

`propertyConstraintViolations()` only. `@Digits(integer = MAX, fraction = 7)` declared
on `interestRate` properties is a standard, zero-cost replacement for the custom
`sevenDecimalPlaceViolation` calls. More broadly, JSR 303 is the right mechanism for any
simple value constraint on future feature properties: non-negative rate enforcement
(`@DecimalMin("0")`), string pattern validation, size constraints. This is the genuine
value JSR 303 brings to this domain — not the current three-construct problem, but the
class of problems it represents.

For `featureLedgerSideApplicability`: JSR 303 has nothing to offer. The constraint is
not on a property value but on the relationship between the submitted classification code's
ledger side and the feature's declared applicability — a cross-context constraint with
no value object to annotate.

For `toFeatureConfiguration()`: JSR 303 has nothing to offer. Read-path construction is
not a validation concern.

**The `@BoundaryGoverned` replacement question:**

A custom JSR 303 `ConstraintValidator` for `@BoundaryGoverned` is the specific case where
JSR 303 could, in principle, replace the current named function. The assessment is that
it is feasible but not an improvement.

The structural problem: a property-level constraint validator on `interestRate` receives
only the property value. It cannot see `effectiveDatetime`, which is on the containing
`PutAccountFeaturesRequest`. Property-level validators in JSR 303 are monadic — they see
one value, not its context. The `effectiveDatetime` check is a cross-field constraint.

The structurally correct JSR 303 form is a class-level constraint on `PutAccountFeaturesRequest`,
with a validator that receives the whole request object. Spring's `LocalValidatorFactoryBean`
(auto-configured by `spring-boot-starter-validation`) supports `@Autowired` injection into
constraint validators, so `ProcessingBoundaryClosureRepository` can be injected. The
technical machinery is available.

What the class-level validator would contain: iteration over `features.presentFeatures()`,
reflection to find `@BoundaryGoverned` properties, and comparison of the business date
of `effectiveDatetime` against the repository-held closure timestamp. This is precisely
the logic of `opennessViolations()`. The logic is not simplified — it is relocated from a
named, directly readable service function into a validator class. The net effect is
increased indirection with no clarity gain.

**The pipeline split problem:**

This is the structural objection that forecloses simple `@Valid` adoption. The current
service accumulates all violations exhaustively and throws a single `NucleusValidationException`
(ADR-020: per-property attribution, total submission rejection, exhaustive collection).
Adding `@Valid` to the controller fires validation before the service method is entered.
A submission with both a `@Digits` violation and a ledger-side applicability violation
would report only the `@Digits` violation — the exhaustive-collection guarantee breaks.

The workaround is programmatic: inject `javax.validation.Validator` into
`AccountFeaturesService`, call `validator.validate(request.features)` explicitly, and
merge the resulting `Set<ConstraintViolation<FeatureConfiguration>>` into the violation
accumulator. This preserves ADR-020 but adds a translation step.

**The translation layer cost:**

`ConstraintViolation` carries a `propertyPath` (e.g. `liabilityInterest.interestRate`)
and a `message` (e.g. "numeric value out of bounds (<999 digits>.<7 digits> expected)").
The current `NucleusViolation` carries `subject` (the feature name, e.g. `"liabilityInterest"`)
and a message that names both the property and the actual scale count. These do not map
directly. Correct translation requires parsing the property path to extract the feature
name as subject, and either accepting the Hibernate Validator message verbatim (different
format from today) or overriding it via `@Digits(message = "...")` (which loses the
ability to interpolate the actual scale count without an EL expression).

This translation layer does not yet exist. Writing it is the hidden cost of Approach C.
Once written, it applies to all future JSR 303 constraints. That amortisation is
genuinely valuable — but it is not free at the point of first introduction.

**Assessment:**

Approach C is not a standalone replacement for Approach A. It addresses only one of the
three hardcoded constructs, requires a translation layer not yet in the codebase, and the
`@BoundaryGoverned` case (the most interesting claim) is feasible but not simpler than
the current named function. The pipeline split problem forecloses `@Valid` on the
controller without additional error-handling architecture.

The correct relationship between Approach A and Approach C: when the Approach A registry
is implemented at the third feature trigger, use `@Digits` from JSR 303 instead of the
custom `@MaxDecimalPlaces(n)` annotation. The registry-driven `propertyConstraintViolations()`
already reads property annotations via reflection — substituting `@Digits` for
`@MaxDecimalPlaces` is a one-annotation change that replaces a bespoke annotation with a
standard library annotation. The translation layer at that point is minimal: read the
`fraction` field from `@Digits` and apply the same scale check already in the prototype.
This is the proportionate use of JSR 303 within the Approach A scope.

A broader JSR 303 integration — programmatic `Validator.validate()` + translation layer,
as the primary validation mechanism for value constraints — is a separate architectural
decision that may be worth taking when the catalogue has more properties with more varied
constraints. It is not the right scope for the third feature trigger.

---

## Decision

Deferral. The inconsistency is accepted at 2 features. Approach A is the agreed
resolution when the trigger condition is reached.

**Trigger condition:** On addition of the third catalogue feature to the codebase,
Approach A must be applied as the first step of the implementation task, before writing
the new feature class. The task should not be written to add the new feature directly to
the current hardcoded constructs. The property-level constraint annotation in the
implementation should be `@Digits` from JSR 303 (already on the classpath) rather than
the custom `@MaxDecimalPlaces(n)` from the prototype; this is the proportionate use of
JSR 303 within the Approach A scope.

**Rationale for this trigger rather than a later one:**
- It is the earliest point at which a developer unfamiliar with the pattern would
  encounter it for the first time.
- It converts 4-5 per-feature touch points to 2 before they multiply further.
- It preserves the pre-version-1 window for any structural changes. After version 1,
  any API-visible changes (including anything touching the `FeatureConfiguration`
  response structure) carry backward-compatibility obligations.

---

## Approach A specification (for the follow-on task)

The prototype is the specification. The follow-on task must produce the following
changes to `AccountFeatures.kt`:

1. Two new annotations: `@CatalogueFeature(ledgerSides: Array<LedgerSide>)` at class
   level, and `@Digits(integer = MAX, fraction = n)` from JSR 303
   (`jakarta.validation.constraints.Digits`) at property level for decimal precision
   constraints. The prototype used a custom `@MaxDecimalPlaces(n)` annotation; the
   implementation should use `@Digits` instead, which is already on the classpath via
   `spring-boot-starter-validation`. The registry-driven `propertyConstraintViolations()`
   reads the `fraction` field from `@Digits` and applies the same scale check.

2. Both existing feature classes annotated: `LiabilityInterestFeature` with
   `@CatalogueFeature(ledgerSides = [LedgerSide.LIAB])` and `AssetInterestFeature` with
   `@CatalogueFeature(ledgerSides = [LedgerSide.ASST])`. Both `interestRate` properties
   annotated `@MaxDecimalPlaces(7)`.

3. `FeatureCatalogueRegistry` Spring component: holds explicit list of
   `KClass<out AccountFeature>`, exposes `ledgerSideApplicability: Map<String, Set<LedgerSide>>`
   computed from `@CatalogueFeature` annotations.

4. `FeatureCatalogueConverter` accepts `FeatureCatalogueRegistry` constructor dependency.
   `toFeatureConfiguration()` uses `registry.featureClasses` to derive the feature name
   to KClass mapping and uses `FeatureConfiguration::class.primaryConstructor!!.callBy(args)`
   to construct the result.

5. `featureLedgerSideApplicability` private val deleted. `AccountFeaturesService` accepts
   `FeatureCatalogueRegistry` constructor dependency and passes
   `registry.ledgerSideApplicability` to `ledgerSideApplicabilityViolations()`.

6. `propertyConstraintViolations()` rewritten using `feature::class.memberProperties` and
   `@MaxDecimalPlaces` annotation discovery — matching the existing `@BoundaryGoverned`
   pattern in `opennessViolations()`.

The prototype is on `worktree-agent-a6585a0e` and is not to be merged. The task should
re-implement cleanly from this specification, driven by TDD in the normal manner.

---

## Pre-work value

The prototype constitutes complete pre-work. The design is established, the implementation
cost is confirmed (~32 net production lines), and the critical technical question —
whether `FeatureConfiguration::class.primaryConstructor!!.callBy()` constructs the
object correctly from a registry-driven map — is answered affirmatively. A task based
on this specification requires no additional design investigation. The prototype branch
should not be merged; the task re-implements from specification under TDD.