# ADR-009: Effective Datetime Granularity in the Account-Features API

**Date:** 2026-03-20
**Status:** Accepted

## Context

The parameter value hierarchy model requires that every parameter value carries an
effective datetime — the UTC timestamp from which that value governs resolution. The
effective datetime is distinct from the write timestamp. This distinction is load-bearing:
it allows future-dated configuration to be registered in advance (a rate change effective
2026-07-01T00:00:00Z submitted on 2026-03-15) and allows late registration of
configuration for open periods (a value submitted today with an effective datetime in the
recent past, within an open period).

The account-features API accepts feature configuration submissions that may include
multiple features and multiple properties within each feature. Each property translates
to an independent parameter key-value write at the Parameter Configuration context.
Because each write requires an effective datetime, the API must define how effective
datetimes are specified for multi-property submissions.

Two options present themselves:

*Per-submission effective datetime.* A single effective datetime is supplied with the
request and applies uniformly to all properties in the submission. A configurer who needs
two properties to become effective at different times makes two separate submissions.

*Per-property effective datetime.* Each property in the submission carries its own
effective datetime. A single request can set `enabled` effective 2026-03-01T00:00:00Z
and `interestRate` effective 2026-07-01T00:00:00Z.

## Decision

The account-features API uses a single, submission-level effective datetime. All
properties in a submission become effective at the same datetime. The effective datetime
is provided as a field in the request body. If omitted, the current datetime is
substituted — this is an explicit input substitution at the API boundary, not an implicit
dependency on system time, and is consistent with the convention established for
resolution datetime defaulting in the parameter value hierarchy model.

## Consequences

**Positive:**

- The API remains simple for the common case. In practice, a configurer submitting a
  rate change or a feature activation expects all properties in the submission to become
  effective simultaneously. Per-property effective datetimes would add complexity that
  serves the uncommon case at the cost of the common one.
- Each submission is an atomic statement of intent: "as of this datetime, these features
  are configured as follows." This is easier to reason about, audit, and reconstruct
  than a submission where different properties within the same request are scattered
  across different effective datetimes.
- The parameter value hierarchy supports multiple values at different effective datetimes
  per key. A configurer who needs different effective datetimes for different properties
  can submit each property (or group of properties with the same effective datetime) in a
  separate request. The underlying model supports this; the API does not need to expose
  it in a single request.
- The default-to-current-datetime behaviour mirrors the resolution datetime convention
  and is consistent with the existing API behaviour for the hypothetical query endpoint.
  A configurer who omits the effective datetime gets the expected behaviour (effective
  now) without needing to supply the datetime explicitly for routine submissions.

**Negative:**

- A configurer who needs to submit properties with different effective datetimes in an
  atomic operation cannot do so. They must submit two requests. If the second request
  fails, the configuration is partially registered. This is an acceptable trade-off in
  the initial implementation — partial registration leaves the hierarchy in a known,
  valid state (the first submission succeeded), and the configurer can retry the second
  submission. A future enhancement could support per-property effective datetimes if the
  demand for atomic multi-datetime submissions becomes material.
- The constraint is invisible to clients who have not read the API documentation. A
  configurer who expects different properties to carry different effective datetimes from
  a single submission will not receive a validation error — all properties will be written
  with the same effective datetime. Whether this is a misuse or expected behaviour depends
  on the configurer's mental model.

**Risks:**

- **Unexpected coupling of effective datetimes.** A configurer who submits `enabled: true`
  and `interestRate: 0.0350000` together, intending them to take effect at different
  times, will discover at resolution time that both are effective from the submission
  datetime. This is a documentation and API design clarity risk, not a correctness risk
  in the technical sense — the system behaves as specified. Clear API documentation is
  the mitigation.

## Alternatives Considered

**Per-property effective datetimes.** Each property in the submission carries its own
effective datetime. This provides maximum flexibility and removes the need for multiple
submissions when effective datetimes differ. Rejected for the initial implementation
because it significantly complicates both the request body structure and the validation
logic without serving the common case. The request body would require either a wrapper
object per property (`{ "value": 0.0350000, "effectiveDatetime": "2026-07-01T00:00:00Z" }`)
or a parallel structure of values and datetimes, both of which are more complex than a
simple property map. This option remains available as a future enhancement and the
underlying parameter model supports it; the API layer can add it without structural
changes to the hierarchy.

**Effective datetime as a query parameter.** The effective datetime is supplied on the
URL as a query parameter rather than in the request body. Rejected because the effective
datetime is intrinsic to the operation — it determines what is written, not how the
resource is addressed. Including it in the request body alongside the feature
configuration treats it as part of the submission intent rather than as a modifier on
the HTTP operation.