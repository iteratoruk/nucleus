# TSK-005: Extract validateAndParseClassificationCode on AccountFeaturesService

**Status:** In Progress

---

## Goal

Extract a private method `validateAndParseClassificationCode(code: String): ClassificationCode`
on `AccountFeaturesService`, replacing the two identical three-line inline validation blocks
in `put()` and `get()` with a single call site each. The system behaviour is identical before
and after; the full test suite must pass unchanged.

## Motivation

`AccountFeaturesService.put()` and `AccountFeaturesService.get()` each contain the same
three-line validation and construction sequence for `ClassificationCode`. If the validation
logic changes — a new structural constraint, a different exception type — both call sites
must be updated consistently. Extraction eliminates the duplication and makes the intent
at each call site explicit. This task was raised by RFP-003 (2026-03-25).

## Scope Boundary

This task covers `AccountFeaturesService` only: the private method extraction and the
replacement of the two duplicate inline blocks. The following are explicitly out of scope:

- `classificationCodeViolation` and `ledgerSidePrefixViolation` — unchanged
- The controller, repositories, and all infrastructure — unchanged
- No new tests are required; existing coverage is sufficient to protect the extraction

## Implementation

Extract the following private method on `AccountFeaturesService`:

```kotlin
private fun validateAndParseClassificationCode(code: String): ClassificationCode {
    val violation = classificationCodeViolation(code) ?: ledgerSidePrefixViolation(code)
    if (violation != null) throw NucleusValidationException(listOf(violation))
    return ClassificationCode(code)
}
```

Replace the three-line block in both `put()` (lines 227–231) and `get()` (lines 287–291)
with:

```kotlin
val code = validateAndParseClassificationCode(classificationCode)
```

## Verification Steps

All tasks must pass the full build before they are complete:

```bash
./gradlew test
./gradlew detekt
./gradlew spotlessCheck
```

Confirm that `AccountFeaturesApiTest` scenarios exercising classification code validation
continue to pass — in particular:

- NUC3-T2: `a submission targeting a malformed classification code is rejected` (PUT path)
- GET scenarios that call the endpoint with valid codes (exercise the extraction indirectly)
- The malformed code and unrecognised ledger-side scenarios (exercise the exception path)

## Findings

None anticipated. This is a small, mechanical extraction with no observable behaviour change.