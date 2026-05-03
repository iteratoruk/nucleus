# ADR-021: JSR 303 as the Primary Validation and Error Handling Strategy

**Date:** 2026-04-06
**Status:** Accepted

## Context

The current validation pattern in Nucleus is entirely manual. Each service method that
requires validation builds a `List<NucleusViolation>` by calling named private functions
— `ledgerSideApplicabilityViolations()`, `propertyConstraintViolations()`,
`opennessViolations()` — accumulates their results, and throws `NucleusValidationException`
if any violations are present. The `ErrorHandler` catches this and produces a structured
400 response carrying the violation list.

This pattern has the following characteristics:

- Every value constraint (decimal precision, non-negativity, required presence) is
  expressed in bespoke code rather than declaratively. Adding a constraint to a new
  property requires writing a new function call and adding it to the accumulation sequence.

- The constraint declarations are not co-located with the properties they govern. A
  reader of `AssetInterestFeature` cannot determine that `interestRate` must be
  non-negative or have at most seven decimal places by reading the class — that
  information lives in `propertyConstraintViolations()` in `AccountFeaturesService`.

- `spring-boot-starter-validation` (Hibernate Validator, the JSR 303 / Jakarta Bean
  Validation reference implementation) is already on the classpath and is not currently
  used. The framework support for declarative constraint declaration, cascading validation,
  exhaustive violation collection, and constraint validator injection is present and idle.

Nucleus is pre-version-1. No external contract obligations constrain the current API
shape or validation error format. Models can be changed if doing so improves the
architecture. This window closes at version 1 declaration.

The immediate trigger for this decision is the catalogue metadata consistency finding
(RFP-002 / SPK-001), which surfaced the question of whether JSR 303 should be adopted
as the mechanism for property-level constraint declaration — and specifically whether it
could extend to domain-specific constraints like boundary-governed openness validation.
The spike established that the adoption is technically feasible but has architectural
implications that warrant a decision before the codebase accumulates further endpoints
and features built on the current manual pattern.

## Decision

JSR 303 is adopted as the primary mechanism for expressing validation constraints in
Nucleus. The following commitments follow from this:

**Constraint declaration is co-located with the constrained type.** Value constraints on
request body properties — decimal precision, non-negativity, format validation, required
presence — are declared using standard JSR 303 annotations (`@Digits`, `@DecimalMin`,
`@NotNull`, `@Pattern`, etc.) directly on the relevant properties. A reader of a request
body class can determine which constraints apply to a property without navigating to a
service method.

**Custom `ConstraintValidator` implementations handle domain constraints.** Constraints
that require access to Spring-managed beans — boundary-governed openness validation,
which must consult `ProcessingBoundaryClosureRepository` — are expressed as custom JSR
303 constraint annotations with corresponding `ConstraintValidator` implementations.
Spring's `LocalValidatorFactoryBean` (auto-configured by `spring-boot-starter-validation`)
supports `@Autowired` injection into constraint validators, making repository access
available inside validators without any additional framework configuration.

**`@Valid` is used on `@RequestBody` parameters in controllers.** Spring MVC triggers
validation before the service method is entered. Violations produce
`MethodArgumentNotValidException`, which `ErrorHandler` handles and translates to the
`NucleusError` response format.

**`ErrorHandler` handles `MethodArgumentNotValidException`.** A new `@ExceptionHandler`
is added to `ErrorHandler` for `MethodArgumentNotValidException`. The handler translates
`FieldError` instances from the binding result to `NucleusViolation` instances and returns
a `NucleusError` with appropriate `NucleusErrorCode`. The translation convention for
nested request body violations follows the pattern `features.{featureName}.{propertyName}`:
the feature name segment (the second segment of the field path) becomes the violation
subject, and the message is taken from the constraint annotation's `message` attribute.
Class-level constraint violations (where no field path exists) must provide the subject
via the violation interpolation context or annotation parameter.

**`NucleusValidationException` is not superseded.** Some domain constraints cannot be
expressed as JSR 303 annotations on the request body because the necessary context is not
available to a constraint validator. The primary example is ledger-side applicability:
the ledger side is derived from the classification code path variable, which is not part
of the request body and is not visible to a `@Valid` cascade. This validation remains a
named function in the service that throws `NucleusValidationException`. The `ErrorHandler`
continues to handle `NucleusValidationException` as it does today. The two mechanisms
coexist; JSR 303 handles structural and value constraints, `NucleusValidationException`
handles domain constraints that require request context unavailable to a body validator.

## Consequences

**Positive:**

- Constraint declarations appear at the point of definition. A new feature property is
  added with its constraints expressed inline — `@Digits(fraction = 7)`,
  `@DecimalMin("0")`, `@BoundaryGoverned(BUSINESS_DAY_CLOSE)` — rather than requiring
  corresponding additions to service-layer functions distributed across the codebase. The
  finding of RFP-002 (that adding a feature requires updates to at minimum four dispersed
  locations) is addressed for the constraint-declaration dimension directly, without
  waiting for the Approach A registry trigger condition.

- JSR 303's built-in exhaustive violation collection applies within the `@Valid` scope.
  Hibernate Validator collects all constraint violations across all validated properties
  and nested objects before returning; `MethodArgumentNotValidException` carries the
  complete set of violations rather than stopping at the first failure. This matches the
  exhaustive collection behaviour of the current manual pattern.

- Standard annotations are self-documenting and tooling-supported. `@Digits(fraction = 7)`
  communicates intent more precisely than a call to `sevenDecimalPlaceViolation()` in a
  function that must be found and read to understand the constraint.

- Spring integration is complete. `LocalValidatorFactoryBean` is already configured.
  Constraint validators can be Spring beans with full dependency injection. No additional
  framework configuration is required beyond adding `@Valid` annotations and writing
  constraint implementations.

- Custom constraint annotations can replace `@BoundaryGoverned` as a JSR 303 constraint
  rather than a bespoke annotation read by `opennessViolations()`. The logic in
  `opennessViolations()` moves into a `ConstraintValidator` implementation that is
  invoked by the framework rather than called manually. The explicit call site in the
  service is eliminated.

**Negative:**

- The `@Valid` cascade fires before the service method. This introduces a two-tier
  validation sequence: `@Valid` fires first (value constraints, boundary governance),
  then the service evaluates domain constraints requiring request context (ledger-side
  applicability). A submission with violations in both tiers will receive only the `@Valid`
  tier violations in response — ledger-side applicability failures are not surfaced until
  a second submission that passes `@Valid` validation. This partially relaxes the
  exhaustive collection guarantee: the current code reports all violation types in a
  single response; the new model reports value violations and domain violations in
  separate attempts. Whether this is acceptable depends on how frequently the two tiers
  produce simultaneous violations; in practice, ledger-side applicability failures are
  orthogonal to value constraint failures (they relate to which feature is submitted, not
  the values of its properties). The practical impact is expected to be low, but it is
  a departure from the current behaviour and must be understood as such.

- `ErrorHandler` gains responsibility for translating the `MethodArgumentNotValidException`
  structure into the `NucleusError` format. The property path to violation subject
  convention must be established and maintained. Deeply nested or unusually structured
  property paths may not map cleanly; this must be considered at the point each new
  request body is designed.

- Class-level JSR 303 constraints (used for cross-field validation such as boundary
  governance, which requires both the property value and the submission's
  `effectiveDatetime`) receive the complete annotated object as their validation target.
  The validator must navigate the object to find the properties it governs. This is more
  complex than property-level validation and requires care to produce violation messages
  that are precisely attributed to the failing property rather than to the class as a
  whole.

- The codebase currently contains manual validation that will coexist with the new
  pattern during the transition period. The transition is deferred (see Adoption
  below); during the deferral, new code should avoid deepening the manual pattern
  unnecessarily but is not obliged to adopt JSR 303 prematurely.

**Risks:**

- **Constraint validator logic untestable in isolation.** Property-level constraint
  validators with no injected dependencies can be unit-tested directly. Constraint
  validators that inject repositories are harder to unit-test without the full Spring
  context. This risk is shared with any Spring-managed component but is worth noting
  for validators that replace currently testable service functions.

- **Message format divergence.** Hibernate Validator produces constraint violation
  messages from annotation `message` attributes, with optional EL interpolation
  (`${validatedValue}`, `{max}`, etc.). The current `NucleusViolation` messages contain
  dynamic values (actual scale count, actual ledger side) that must be replicated. If the
  messages are not carefully specified, the violation messages visible to API consumers
  will change as constraints are migrated, which may affect client integrations built
  against the current message format. This is low-risk at pre-v1 but must be addressed
  before any consumer builds against the current message text.

- **Constraint annotation on `@BoundaryGoverned` requires coordination with SPK-001
  trigger condition.** The Approach A task (triggered by the third catalogue feature)
  currently assumes `@BoundaryGoverned` is retained as a bespoke annotation read by the
  registry-driven `opennessViolations()`. If this ADR is accepted before that task is
  written, the Approach A specification must be revised: `@BoundaryGoverned` becomes a
  JSR 303 constraint annotation rather than a bespoke one, and `opennessViolations()` is
  replaced by the constraint validator rather than extended by the registry.

## Adoption

The decision is accepted, but the implementation is deferred. The current manual
validation pattern continues in use until a complexity trigger condition makes the
migration to JSR 303 worth the disruption. The trigger condition is the introduction
of the account features and servicing work: those areas will add new properties and
new constraint kinds at a volume where the dispersed-function cost of the manual
pattern becomes acute. Migration to JSR 303 should accompany that work rather than
precede it.

Until the trigger condition is met, the principle of this ADR is settled and binding
on any new validation work — new constraints added in the meantime should be evaluated
against the JSR 303 pattern's expected shape and should not be designed in ways that
would obstruct the eventual migration — but the wholesale conversion of existing
validation code to JSR 303 is not required and should not be undertaken speculatively.
When the account features and servicing work begins, the migration is undertaken
alongside the new work and the manual pattern is retired together with the existing
code paths it serves.

## Relationship to prior ADRs

**ADR-013 (Exception ownership and error handler centralisation):** This ADR extends
ADR-013 rather than superseding it. ADR-013's architectural principle — root package
owns exception types, single `@ControllerAdvice` — is preserved. `ErrorHandler` remains
the single `@ControllerAdvice`; it gains one new `@ExceptionHandler` for
`MethodArgumentNotValidException`. Exception types remain root-owned. `NucleusValidationException`
continues to exist and be used for constraints inexpressible in JSR 303. The two
`@ExceptionHandler` methods in `ErrorHandler` are not in conflict: `MethodArgumentNotValidException`
is a Spring MVC framework exception, not a bounded context exception; its handling in
the root `ErrorHandler` does not require the root package to import from any sub-package.

**ADR-020 (Per-property openness validation):** ADR-020's requirements — per-property
attribution, exhaustive collection, total submission rejection — are satisfied within the
`@Valid` scope. All violations found during the `@Valid` pass are collected exhaustively;
the response carries all of them. The partial relaxation described in the Negative section
above concerns the cross-tier boundary (between `@Valid` violations and subsequent service
validation), not within-tier exhaustiveness. ADR-020's requirements remain met for the
openness validation layer specifically.

**SPK-001 (Catalogue metadata consistency):** The Approach A trigger condition established
in the SPK-001 recommendation remains valid. However, if this ADR is accepted, the
Approach A specification must be updated: the property-level constraint annotation for
decimal precision should be `@Digits` from JSR 303, and `@BoundaryGoverned` should be
converted to a JSR 303 constraint annotation at the same time — rather than remaining a
bespoke annotation read by a registry. The Approach A task document should be written
against this ADR's model, not against the prototype's bespoke annotation model.

## Open Questions

**OQ-1: Error code granularity under JSR 303.**

`NucleusErrorCode.INVALID_FEATURE_CONFIGURATION` is currently the single code used for
all `NucleusValidationException` responses. ADR-013 notes that if future contexts require
distinct codes, the code should be moved into the exception constructor. Under JSR 303,
`MethodArgumentNotValidException` carries no `NucleusErrorCode`; the `ErrorHandler`
must assign one. The question is whether to introduce a mechanism for constraint
annotations to carry or imply an error code, or whether to use a single code for all
`@Valid` failures. If distinct codes are desirable — for example, distinguishing a
malformed request body from a domain rule violation — this must be designed before the
`ErrorHandler` translation is implemented.

**OQ-2: Authoritative violation subject convention for the `ErrorHandler` translation.**

The `NucleusViolation.subject` field is currently populated by service code that knows
the domain. Under JSR 303, the `ErrorHandler` must derive the subject from the field
path in a `FieldError` (e.g. `features.liabilityInterest.interestRate` → subject
`"liabilityInterest"`). This derivation must be specified precisely and must handle edge
cases: top-level fields, single-segment paths, class-level constraint violations (no
field path), and potential future nesting patterns. If the derivation is defined by
convention (second segment of `features.*` paths is the subject), that convention must
be documented and enforced at request body design time.

**OQ-3: Ledger-side applicability — long-term placement.**

Ledger-side applicability currently cannot be expressed as a `@Valid` body constraint
because the ledger side is derived from the classification code path variable. The
decision to keep it as a manual service check is accepted as the current resolution. The
longer-term question — whether a different request body design (for instance, including
the effective ledger side in the request body as a redundant but validatable field, or
via a method-level constraint that receives path variables) — should be evaluated when
the `PutAccountFeaturesRequest` model is next revisited. This is not a blocker for
adopting JSR 303 for value constraints, but it is a gap in the uniformity the approach
aspires to.

## Alternatives Considered

**Retain the current manual validation pattern.** A consistent, working approach. Rejected
on the grounds that `spring-boot-starter-validation` is already present, the existing
pattern does not scale well as constraints multiply across features, and the constraint
declarations are detached from the types they govern. The pre-v1 window is the correct
time to establish a long-term pattern.

**Programmatic JSR 303 (no `@Valid` on controller; `validator.validate()` in service).**
The `javax.validation.Validator` bean is injected into service methods. Validation is
triggered explicitly in the service, and `ConstraintViolation` results are merged with
manually-produced violations before a single `NucleusValidationException` is thrown.
This preserves the exhaustive cross-tier collection property and keeps all validation
logic in the service layer. It gives up Spring MVC's automatic validation integration and
requires a `ConstraintViolation` → `NucleusViolation` translation layer. It is a valid
alternative that resolves the two-tier concern at the cost of more boilerplate. It is
not adopted as the primary approach because it treats `@Valid` / MVC integration as
actively harmful rather than accepting a well-understood tiering boundary. If the two-tier
concern proves to be a practical problem for API consumers, this alternative should be
revisited.

**Extend the bespoke annotation pattern (Approach A from SPK-001).** Generalise the
existing `@BoundaryGoverned` / reflection-based approach into a full catalogue registry
that drives all constraint evaluation from custom annotations. Rejected as the primary
mechanism: this re-invents what JSR 303 already provides, at the cost of bespoke
infrastructure that will need to be maintained independently. Approach A remains useful
as a structural mechanism for driving catalogue metadata from annotations, but the
validation execution should be delegated to the JSR 303 lifecycle rather than to a
custom reflection-based dispatch.