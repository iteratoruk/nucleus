# ADR-009: Effective Date Granularity in the Account-Features API

**Date:** 2026-03-20
**Status:** Accepted

## Context

The parameter value hierarchy model requires that every parameter value carries an
effective date — the date from which that value governs resolution. The effective date
is distinct from the write timestamp. This distinction is load-bearing: it allows future-
dated configuration to be registered in advance (a rate change effective 2026-07-01
submitted on 2026-03-15) and allows late registration of configuration for open periods
(a value submitted today with an effective date in the recent past, within an open
period).

The account-features API accepts feature configuration submissions that may include
multiple features and multiple properties within each feature. Each property translates
to an independent parameter key-value write at the Parameter Configuration context.
Because each write requires an effective date, the API must define how effective dates
are specified for multi-property submissions.

Two options present themselves:

*Per-submission effective date.* A single effective date is supplied with the request
and applies uniformly to all properties in the submission. A configurer who needs two
properties to become effective on different dates makes two separate submissions.

*Per-property effective date.* Each property in the submission carries its own effective
date. A single request can set `enabled` effective 2026-03-01 and `interestRate`
effective 2026-07-01.

## Decision

The account-features API uses a single, submission-level effective date. All properties
in a submission become effective on the same date. The effective date is provided as a
field in the request body. If omitted, the current date is substituted — this is an
explicit input substitution at the API boundary, not an implicit dependency on system
time, and is consistent with the convention established for resolution date defaulting in
the parameter value hierarchy model.

## Consequences

**Positive:**

- The API remains simple for the common case. In practice, a configurer submitting a
  rate change or a feature activation expects all properties in the submission to become
  effective simultaneously. Per-property effective dates would add complexity that serves
  the uncommon case at the cost of the common one.
- Each submission is an atomic statement of intent: "as of this date, these features
  are configured as follows." This is easier to reason about, audit, and reconstruct
  than a submission where different properties within the same request are scattered
  across different effective dates.
- The parameter value hierarchy supports multiple values at different effective dates
  per key. A configurer who needs different effective dates for different properties can
  submit each property (or group of properties with the same effective date) in a
  separate request. The underlying model supports this; the API does not need to expose
  it in a single request.
- The default-to-current-date behaviour mirrors the resolution date convention and is
  consistent with the existing API behaviour for the hypothetical query endpoint. A
  configurer who omits the effective date gets the expected behaviour (effective now)
  without needing to supply the date explicitly for routine submissions.

**Negative:**

- A configurer who needs to submit properties with different effective dates in an atomic
  operation cannot do so. They must submit two requests. If the second request fails,
  the configuration is partially registered. This is an acceptable trade-off in the
  initial implementation — partial registration leaves the hierarchy in a known, valid
  state (the first submission succeeded), and the configurer can retry the second
  submission. A future enhancement could support per-property effective dates if the
  demand for atomic multi-date submissions becomes material.
- The constraint is invisible to clients who have not read the API documentation. A
  configurer who expects different properties to carry different effective dates from a
  single submission will not receive a validation error — all properties will be written
  with the same effective date. Whether this is a misuse or expected behaviour depends
  on the configurer's mental model.

**Risks:**

- **Unexpected coupling of effective dates.** A configurer who submits `enabled: true`
  and `interestRate: 0.0350000` together, intending them to take effect on different
  dates, will discover at resolution time that both are effective from the submission
  date. This is a documentation and API design clarity risk, not a correctness risk in
  the technical sense — the system behaves as specified. Clear API documentation is the
  mitigation.

## Alternatives Considered

**Per-property effective dates.** Each property in the submission carries its own
effective date. This provides maximum flexibility and removes the need for multiple
submissions when effective dates differ. Rejected for the initial implementation because
it significantly complicates both the request body structure and the validation logic
without serving the common case. The request body would require either a wrapper object
per property (`{ "value": 0.0350000, "effectiveDate": "2026-07-01" }`) or a parallel
structure of values and dates, both of which are more complex than a simple property map.
This option remains available as a future enhancement and the underlying parameter model
supports it; the API layer can add it without structural changes to the hierarchy.

**Effective date as a query parameter.** The effective date is supplied on the URL as a
query parameter rather than in the request body. Rejected because the effective date is
intrinsic to the operation — it determines what is written, not how the resource is
addressed. Including it in the request body alongside the feature configuration treats
it as part of the submission intent rather than as a modifier on the HTTP operation.