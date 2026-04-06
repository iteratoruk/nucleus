# RFP-001: PUT response must resolve node state, not echo submitted features

**Date:** 2026-03-25
**Status:** Proposed
**Bounded context:** Account Feature Catalogue
**Produced by:** Code review session on 2026-03-25

---

## Finding

`AccountFeaturesService.put()` constructs its response at line 247 as:

```kotlin
val response = AccountFeaturesResponse(features = request.features)
```

This returns only the features that were submitted in the request. It does not call
`parameterNodeService.resolve()`. By contrast, `AccountFeaturesService.get()` correctly
performs a full resolution walk:

```kotlin
return AccountFeaturesResponse(
    features = featureCatalogueConverter.toFeatureConfiguration(
        parameterNodeService.resolve(code, asAt)
    )
)
```

The domain model (`docs/architecture/account-features.md`, PUT response specification)
states:

> "The resolved feature configuration for the classification code as of the submitted
> effective datetime, in the same strongly-typed account features representation used by
> the GET endpoint. The resolution traversal begins at the submitted classification node
> and walks up to the global node; account-level values are not included. This allows the
> caller to confirm the effective state of the node after writing, including values
> inherited from ancestor nodes that were not part of the current submission."

A configurer who submits `liabilityInterest.interestRate` against a child node that
inherits `liabilityInterest.enabled` from a parent node will receive a PUT response that
omits `enabled`. The GET response for the same code and datetime would include it. The two
endpoints are inconsistent, and the PUT response provides no confirmation of effective
node state — which is its stated purpose.

No existing test covers the case of a PUT response containing values inherited from
ancestor nodes. The current tests verify only that submitted properties are echoed back,
which masks the deviation from the contract.

## Benefit

After this change, the PUT response is consistent with the GET response for the same
classification code and effective datetime. Callers receive confirmation of the full
effective state of the node after writing — including inherited values — without issuing
a separate GET. The PUT and GET endpoints honour the same documented contract.

## Proposed Approach

After the write to `parameterNodeService.write()` succeeds, replace the response
construction with a resolution call using the submitted `effectiveDatetime` as the
`asAt` parameter:

```
val response = AccountFeaturesResponse(
    features = featureCatalogueConverter.toFeatureConfiguration(
        parameterNodeService.resolve(code, request.effectiveDatetime)
    )
)
```

The idempotency record stores the resolved response. A no-op resubmission therefore
returns the resolved response from the original call, which is correct: the stored
response was accurate at the time of the original write and the idempotency contract
guarantees it is returned unchanged.

Note that this changes the semantics of the idempotency-stored response for existing
idempotent operation records. Any in-flight records created by the current implementation
will return an echo response on resubmission. This is a one-time migration concern for
pre-production; no migration strategy is required at this stage, but this change
constitutes a breaking change to the stored response format for this operation and the
obligation described in ADR-015 is activated.

## Scope

Production code affected:
- `AccountFeaturesService.put()` — replace response construction
- No changes to `ParameterNodeService`, `FeatureCatalogueConverter`, controller, or
  idempotency infrastructure

Tests affected:
- `AccountFeaturesApiTest` — existing tests that assert on PUT response fields will
  continue to pass (they verify that submitted values appear in the response, which
  remains true after resolution). New tests must be added before the refactoring
  proceeds (see Verification Criterion).

## Verification Criterion

Existing test coverage does not protect this specific behaviour: no test submits a
partial PUT to a child node and asserts that the PUT response includes inherited values
from a parent node.

The following tests must be added before the refactoring proceeds, as prerequisites:

1. Configure `liabilityInterest.interestRate` at `LIAB`. Submit a PUT to `LIAB_INAS`
   with only `liabilityInterest.enabled`. Assert that the PUT response includes both
   `interestRate` (inherited from `LIAB`) and `enabled` (submitted). This establishes
   the observable contract.

2. Submit an idempotent resubmission of the above, and assert that the no-op response
   also includes the inherited `interestRate`. This confirms idempotency stores the
   resolved response.

Once these tests exist and fail against the current implementation, the refactoring
proceeds. All existing `AccountFeaturesApiTest` scenarios serve as regression coverage
after the change. The GET behaviour is unaffected.

## Risk

**Risk: performance.** The refactored PUT path issues a resolution walk after every
successful write. The resolution query
(`findByParameterNodeAndEffectiveDatetimeLessThanEqualAndSupersededAtIsNull`) is indexed
on `(parameter_node_id, parameter_key, effective_datetime)` per migration V002, and the
hierarchy depth is bounded by the classification code segment count. The additional
query is unlikely to be materially significant in a write path that already creates
nodes and writes parameter values. No mitigation is needed at this scale.

**Risk: behaviour change in idempotency-stored response.** As noted in the scope, any
idempotent operation records created by the current implementation will return an echo
response. In production this would be a latent inconsistency. At the current
pre-production stage, no mitigation is needed beyond documenting the change as a
breaking serialisation change per ADR-015.

## Work Unit Classification

**Task (TSK-NNN).** This is behaviour-preserving restructuring of the response
construction only — the write path, validation, and idempotency mechanics are unchanged.
The prerequisite tests (two new `AccountFeaturesApiTest` scenarios) must be added and
verified to fail before the implementation change. `chore:` commit prefix.

## Decision

_Pending review._