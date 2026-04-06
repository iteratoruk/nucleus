# RFP-003: Classification code validation is duplicated between put() and get()

**Date:** 2026-03-25
**Status:** Accepted → TSK-005
**Bounded context:** Account Feature Catalogue
**Produced by:** Code review session on 2026-03-25

---

## Finding

`AccountFeaturesService` contains the following identical three-line block in both
`put()` and `get()`:

```kotlin
val codeViolation = classificationCodeViolation(classificationCode)
    ?: ledgerSidePrefixViolation(classificationCode)
if (codeViolation != null) throw NucleusValidationException(listOf(codeViolation))
val code = ClassificationCode(classificationCode)
```

In `put()` this appears at lines 227–231. In `get()` it appears at lines 287–291. The
logic is identical: validate format, validate ledger-side prefix, throw if invalid,
construct a `ClassificationCode`. If this validation sequence changes — for example,
if a new structural constraint is added to classification codes, or if the exception
type changes — both call sites must be updated consistently.

## Benefit

The validation sequence exists in one place. A change to classification code validation
logic propagates to both endpoints without the risk of one being updated and the other
missed. The intent of the code at the service method boundary is clearer: the call site
reads "validate and parse" rather than restating the validation sequence inline.

## Proposed Approach

Extract a private method on `AccountFeaturesService`:

```
private fun validateAndParseClassificationCode(code: String): ClassificationCode {
    val violation = classificationCodeViolation(code) ?: ledgerSidePrefixViolation(code)
    if (violation != null) throw NucleusValidationException(listOf(violation))
    return ClassificationCode(code)
}
```

Both `put()` and `get()` replace their three-line blocks with:

```kotlin
val code = validateAndParseClassificationCode(classificationCode)
```

The `classificationCodeViolation` and `ledgerSidePrefixViolation` private top-level
functions are unchanged.

## Scope

- `AccountFeaturesService` — extract private method, update two call sites
- `classificationCodeViolation` and `ledgerSidePrefixViolation` — unchanged
- No changes to controller, repository, or infrastructure

## Verification Criterion

`AccountFeaturesApiTest` has scenarios that exercise classification code validation via
both the PUT path (NUC3-T2, `a submission targeting a malformed classification code is
rejected`) and implicitly via the GET path (the GET endpoint is called in multiple
resolution scenarios with valid codes). The malformed code and unrecognised ledger-side
scenarios exercise the exception path. All must continue to pass.

No new tests are needed before this refactoring: the existing scenarios are sufficient
to protect the extraction.

## Risk

This is a small, mechanical extraction with no observable behaviour change. The sole
risk is an accidental logic change during the extraction, which the existing tests
would catch. No further mitigation is needed.

## Work Unit Classification

**Task (TSK-NNN).** Behaviour-preserving extraction of duplicate inline code to a
private method. `chore:` commit prefix.

## Decision

Accepted. The proposed extraction is straightforward and the existing test coverage is
sufficient to protect it. Task document: TSK-005.