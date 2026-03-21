# ADR-013: Exception Ownership and Error Handler Centralisation

## Status

Accepted.

## Context

During the implementation of NUC-003, validation logic in the `accountfeatures` bounded
context needed to produce structured HTTP error responses carrying a list of
`NucleusViolation` instances. The initial implementation introduced a
`FeatureConfigurationException` in the `accountfeatures` package and a corresponding
`@ControllerAdvice` (`AccountFeaturesErrorHandler`) in the same package, to avoid a
circular dependency: `accountfeatures` already depends on the root `iterator.nucleus`
package, and placing the exception handler in the root `ErrorHandler` would require the
root package to import from `accountfeatures`, closing the cycle.

This produced two error-handling surfaces: the root `ErrorHandler` (handling
infrastructure-level exceptions such as missing headers) and `AccountFeaturesErrorHandler`
(handling domain validation exceptions). As the codebase grows, each new bounded context
would introduce its own advisor, resulting in a dispersed error-handling topology with no
single point of review.

## Decision

All exception types used to produce HTTP error responses are defined in the root
`iterator.nucleus` package. Sub-packages are conformist: they import and throw exception
types defined at the root. The root `ErrorHandler` is the single `@ControllerAdvice`
responsible for translating all exceptions into HTTP responses.

`NucleusValidationException(violations: List<NucleusViolation>)` is introduced in the
root package as the standard mechanism for structured validation failure. Any bounded
context that needs to report one or more validation violations throws
`NucleusValidationException`. The root `ErrorHandler` translates it to a 400 response
carrying `NucleusError(INVALID_FEATURE_CONFIGURATION, ..., violations)`.

The dependency direction is preserved: sub-packages depend on root; root does not depend
on sub-packages. Exception types are root-owned infrastructure, not bounded-context
artefacts.

## Consequences

A new exception type for a new category of HTTP error (e.g. a 409 conflict, a 403
authorisation failure) must be defined in the root package and handled in `ErrorHandler`.
Sub-packages do not define their own advisors. This keeps error handling reviewable from
a single location and prevents the root package from accumulating import dependencies on
sub-packages.

The `INVALID_FEATURE_CONFIGURATION` error code in `NucleusErrorCode` is currently the
only code associated with `NucleusValidationException`. If future validation contexts
require distinct error codes (e.g. to distinguish classification code structural failures
from feature property constraint failures), the error code should be moved into the
`NucleusValidationException` constructor rather than hard-coded in the handler.

## Relationship to ADR-012

This decision is a direct extension of ADR-012's acyclic dependency rule. ADR-012
establishes the package dependency graph and the principle that the graph must be acyclic.
This ADR applies that principle specifically to the error-handling layer, resolving the
tension between the desire for centralised error handling and the package dependency
constraints that would otherwise force dispersal.