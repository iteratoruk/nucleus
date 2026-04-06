# TSK-004: PUT response must resolve node state, not echo submitted features

**Status:** Complete

---

## Goal

Replace the echo response construction in `AccountFeaturesService.put()` with a full
resolution walk identical to the one performed by `get()`, so that the PUT response
reflects the complete resolved feature configuration — including values inherited from
ancestor nodes — rather than only the features submitted in the request.

## Motivation

The PUT endpoint's response diverges from the documented contract (see
`docs/architecture/account-features.md`) and from the GET endpoint's behaviour for the
same classification code and effective datetime. RFP-001 documents the finding and
proposed resolution. This task implements it.

## Scope Boundary

- Only `AccountFeaturesService.put()` is modified — specifically the response
  construction on line 247.
- `ParameterNodeService`, `FeatureCatalogueConverter`, the controller, and the
  idempotency infrastructure are untouched.
- Two prerequisite integration tests are added to `AccountFeaturesApiTest` as specified
  in RFP-001's Verification Criterion section. No other test changes are made.
- Any performance optimisation of the resolution walk is out of scope.
- Any migration strategy for pre-existing idempotency records is out of scope (recorded
  as a finding below).

## Verification Steps

All tasks must pass the full build before they are complete:

```bash
./gradlew test
./gradlew detekt
./gradlew spotlessCheck
```

Task-specific verification:
- Both prerequisite tests (`AccountFeaturesApiTest`) must fail (red) against the
  unmodified production code before Phase 2 begins.
- Both prerequisite tests must pass (green) after the production code change.
- All pre-existing `AccountFeaturesApiTest` scenarios must continue to pass.

## Execution Record

- Both prerequisite tests (`PUT response includes values inherited from ancestor nodes`
  and `PUT idempotent resubmission response includes values inherited from ancestor
  nodes`) were confirmed red against the unmodified production code before the
  implementation change was applied.
- The production code change was a single replacement in `AccountFeaturesService.put()`
  at line 247: `AccountFeaturesResponse(features = request.features)` replaced with a
  `parameterNodeService.resolve()` call matching the GET path.
- All 21 `AccountFeaturesApiTest` scenarios pass after the change, including both new
  prerequisite tests.
- `./gradlew test`, `./gradlew detekt`, and `./gradlew spotlessCheck` all pass against
  the modified codebase.

## Findings

**Finding 1:** This change constitutes a breaking change to the idempotency-stored
response format for the PUT account-features operation. Any idempotent operation records
created by the previous implementation will return an echo response on resubmission
rather than the resolved response. The obligation described in ADR-015 is activated:
breaking changes to serialised response types require a migration strategy before
production deployment. At the current pre-production stage, no migration is required,
but the breaking change is recorded here per ADR-015.
**Type:** Task
**Suggested identifier:** TSK-005
**Detail:** Define and apply a migration strategy for pre-existing idempotent operation
records for `PUT_ACCOUNT_FEATURES` before the first production deployment of this
change. The migration must either update the stored response bodies to the resolved
format or invalidate the existing records so they are re-executed on next submission.