# ADR-020: Per-Property Openness Validation in Mixed-Category Submissions

**Date:** 2026-03-22
**Status:** Accepted

## Context

ADR-017 establishes three openness categories: `GLOBAL` (no constraint), named
processing boundary categories (e.g. `BUSINESS_DAY_CLOSE`), and `PROSPECTIVE_ONLY`.
A single account-features submission may contain properties from different categories —
for example, `liabilityInterest.enabled` (`GLOBAL`) and `fixedTerm.termPeriod`
(`PROSPECTIVE_ONLY`) in the same PUT request, or `liabilityInterest.enabled` (`GLOBAL`)
and `liabilityInterest.interestRate` (`BUSINESS_DAY_CLOSE`) in the same request. The
properties must be evaluated against different constraints. The question is whether the
submission is validated as a whole against the most restrictive constraint present, or
whether each property is validated against its own category independently.

This decision also bears on the error response: the account-features API requires
exhaustive validation (all errors collected and returned together, per the principle
established in the configurer persona constraints). The error response for an openness
violation must be equally actionable and precisely attributed.

## Decision

**Each property in a submission is validated against its own openness declaration
independently.** A `GLOBAL` property carries no constraint and always passes. A
`BUSINESS_DAY_CLOSE` property is checked against the `BUSINESS_DAY_CLOSE` closure
projection. A `PROSPECTIVE_ONLY` property is checked against the wall-clock time. No
cross-property constraint is applied; the openness constraint for one property does not
affect the validation of any other property in the same submission.

**If any property fails its openness validation, the entire submission is rejected.**
This is consistent with the total rejection principle established in ADR-002 and the
exhaustive validation principle that applies to all account-features submissions. No
partial application occurs: if two of five submitted properties pass their openness
check and three fail, no writes are issued for any of the five. The submission succeeds
as a whole or fails as a whole.

**All openness violations in a submission are reported together.** The rejection error
carries one structured violation record per failing property. Each violation record
identifies:

- The feature name and property name that failed validation.
- The openness category that was violated: the boundary category name (for
  boundary-governed violations) or `PROSPECTIVE_ONLY` (for prospective-only
  violations).
- For boundary-governed violations: the effective datetime's business date and the
  boundary category whose closed set contains it.
- For `PROSPECTIVE_ONLY` violations: the submitted effective datetime and the
  wall-clock time at validation.

A configurer who submits a request with multiple openness violations receives a single
response containing all of them. They do not discover violations one at a time.

**The most-restrictive-wins alternative is rejected.** Applying the most restrictive
constraint across all properties would mean that the presence of a `PROSPECTIVE_ONLY`
property with a past effective datetime also rejects the `GLOBAL` and
`BUSINESS_DAY_CLOSE` properties in the same submission, even if those properties would
individually be accepted. This conflates independent constraints and produces error
responses that do not accurately identify which properties failed and why. It is an
information loss that makes the error less actionable, not more safe.

**Openness validation is part of the exhaustive validation sequence.** It occurs after
structural and type validation and before any writes are issued. The sequence is:
structural correctness of the classification code, ledger-side determination, feature
name validation, ledger-side applicability, property type and value validation, openness
category validation, then writes. Openness violations are collected alongside type and
value violations and returned in the same structured response.

## Consequences

**Positive:**

- Error responses precisely attribute each openness violation to the property and
  constraint responsible. A configurer who mixes a `PROSPECTIVE_ONLY` property with a
  past effective datetime and a `GLOBAL` property receives a rejection identifying only
  the `PROSPECTIVE_ONLY` violation. They know exactly what to fix.
- The validation logic per property is simple and independent: each property is checked
  against its own constraint with no knowledge of what other properties are in the
  submission.
- Consistent with the exhaustive validation principle already established. No new
  pattern is introduced.

**Negative:**

- A submission that contains properties with incompatible effective datetime
  requirements — for example, a `BUSINESS_DAY_CLOSE` property needing a past effective
  datetime and a `PROSPECTIVE_ONLY` property in the same submission — must be split
  into two separate requests. The account-features API does not support per-property
  effective datetimes (ADR-009). When the correct effective datetimes differ by openness
  category, the configurer must submit separately.

**Risks:**

- **Submission splitting awareness.** Configurers unaware of per-property openness
  classifications may attempt to submit properties with incompatible effective datetime
  requirements in a single request. The error response must be sufficient to explain
  which property failed its openness constraint so the configurer can restructure the
  submission. This is an API ergonomics concern, not a correctness risk.

## Alternatives Considered

**Most-restrictive-wins.** The entire submission is validated against the most
restrictive constraint present. If a `PROSPECTIVE_ONLY` property is included, all
properties are subject to the `PROSPECTIVE_ONLY` constraint. Rejected: this produces
misleading rejection messages, prevents late registration for boundary-governed
properties whenever a `PROSPECTIVE_ONLY` property is co-submitted, and conflates
independent constraints that should remain independent. It is simpler to implement but
less correct and less actionable.

**Per-property effective datetimes.** Each property in the submission carries its own
effective datetime, allowing `PROSPECTIVE_ONLY` and `BUSINESS_DAY_CLOSE` properties to
coexist in a single submission with different effective datetimes. Rejected by ADR-009:
a single effective datetime per submission applies to all properties. Per-property
effective datetimes add significant API complexity for a use case that can be served by
multiple submissions. This decision does not reopen ADR-009.