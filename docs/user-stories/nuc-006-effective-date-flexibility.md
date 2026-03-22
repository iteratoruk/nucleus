## NUC-006: Register account feature configuration effective from a datetime other than now

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to submit account feature configuration with an effective datetime in the future or in a past open period,
so that I can pre-configure product features ahead of their launch and register configuration late without compromising the integrity of unfinished processing.

**Background:**
Effective datetime and write datetime are distinct. The effective datetime is a UTC timestamp with a resolution of one second. A future effective datetime allows Cameron to pre-stage configuration for a product before its launch; the values are not applicable for resolution until the resolution datetime reaches or passes the effective datetime. A past effective datetime within an open period is late registration: because no financial processing for that period has been finalised, the submission carries no consistency risk and must be accepted. These two cases are both expressions of the same fundamental design principle: the effective datetime governs when a value applies, not when it was written.

**Scenarios:**

### Scenario: Configuration submitted with a future effective datetime does not govern current resolution

```gherkin
Given no account feature configuration exists for classification code "LIAB_INAS_2026" with an effective datetime on or before 2026-03-20T12:00:00Z
When Cameron submits account feature configuration for classification code "LIAB_INAS_2026" with an effective datetime of 2026-04-01T00:00:00Z
Then the submission is accepted
And no applicable value exists for classification code "LIAB_INAS_2026" at resolution datetime 2026-03-20T12:00:00Z
And the submitted features are the applicable configuration for classification code "LIAB_INAS_2026" at resolution datetime 2026-04-01T00:00:00Z
```

### Scenario: Configuration submitted with a past effective datetime in an open period is immediately applicable

```gherkin
Given the period containing 2026-03-01T00:00:00Z is an open period
And no account feature configuration exists for classification code "LIAB_INAS_2026"
When Cameron submits account feature configuration for classification code "LIAB_INAS_2026" with an effective datetime of 2026-03-01T00:00:00Z
Then the submission is accepted
And the submitted features are the applicable configuration for classification code "LIAB_INAS_2026" at resolution datetime 2026-03-01T00:00:00Z
And the submitted features are the applicable configuration for classification code "LIAB_INAS_2026" at resolution datetime 2026-03-20T12:00:00Z
```

**Out of Scope:**
- Configuration with an effective datetime in a closed period — that is rejected, covered in NUC-007.

**Open Questions:**
None.

---

## Architecture Impact Note (from processing boundary session, 2026-03-22)

**What the model now means for this story:**

Both properties used in the scenarios (`liabilityInterest.enabled` and
`liabilityInterest.interestRate`) are GLOBAL-boundary properties. The story as written
remains correct, but the Given conditions can now be stated precisely:

- "The period containing 2026-03-01T00:00:00Z is an open period" now means: the
  GLOBAL boundary's closed-date set does not contain the business date 2026-03-01. In
  the current pre-production state, the GLOBAL boundary has no closed dates, so this
  Given is trivially satisfied.

**What should change before TDD begins:**

The scenarios do not need to be revised for the GLOBAL-boundary properties they cover.
However, the story does not address the NEVER openness category at all. A new scenario
is required to cover submission of a NEVER property with a past effective datetime — a
third case of effective datetime semantics distinct from both the future-dating and
open-period-backdating cases already covered. This scenario may be:

- Added to this story as a third scenario (consistent with the story's theme of
  effective datetime flexibility), or
- Added to NUC-007 as a parallel rejection scenario alongside the closed-period
  rejection cases.

The latter placement is recommended: NUC-007 is already the story for "effective
datetime constraints that cause rejection," and NEVER violations are a form of rejection
constraint. The distinction between them (NEVER is a structural property constraint;
closed-period is a boundary-state constraint) should be reflected in separate scenarios
with distinct Given conditions, both in NUC-007.

**The key scenario to add (placement: NUC-007):**

A submission for a `PROSPECTIVE_ONLY` property (e.g. `fixedTerm.termPeriod`) with a past
effective datetime is rejected, and the rejection error identifies the property, the
`PROSPECTIVE_ONLY` constraint, the submitted effective datetime, and the wall-clock time at
validation. This scenario requires `fixedTerm` feature implementation, which is not yet
scoped. It should be held until the fixed term feature story is written.