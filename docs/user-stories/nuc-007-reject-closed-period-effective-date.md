## NUC-007: Reject account feature configuration with an effective datetime in a closed period

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want Nucleus to reject any account feature configuration whose effective datetime falls within a closed period,
so that the configuration basis of already-finalised financial processing is protected from retroactive change.

**Background:**
A closed period is one for which Nucleus has completed all scheduled processing and whose financial record is considered final. Setting or superseding a parameter value with an effective datetime in a closed period would alter the basis on which immutable ledger entries were produced. Nucleus enforces this boundary at write time. A submission with a past effective datetime in an open period is not subject to this restriction and is accepted as late registration.

**Scenarios:**

### Scenario: A new submission with an effective datetime in a closed period is rejected

```gherkin
Given the period containing 2026-02-01T00:00:00Z is a closed period
When Cameron submits account feature configuration for classification code "LIAB_INAS_2026" with an effective datetime of 2026-02-01T00:00:00Z
Then the submission is rejected
And the rejection states that the effective datetime 2026-02-01T00:00:00Z falls within a closed period
And no parameter node is created or modified
```

### Scenario: A supersession attempt for a closed-period effective datetime is rejected

```gherkin
Given the period containing 2026-02-01T00:00:00Z is a closed period
And account feature configuration for classification code "LIAB_INAS_2026" includes feature "F" with active value "V1" at effective datetime 2026-02-01T00:00:00Z
When Cameron submits account feature configuration for classification code "LIAB_INAS_2026" with feature "F" set to value "V2" and effective datetime 2026-02-01T00:00:00Z
Then the submission is rejected
And the rejection states that the effective datetime 2026-02-01T00:00:00Z falls within a closed period
And the applicable value of feature "F" for classification code "LIAB_INAS_2026" at effective datetime 2026-02-01T00:00:00Z remains "V1"
```

**Out of Scope:**
- The definition of what constitutes period close, which context owns and signals the close event, and how Parameter Configuration enforces the boundary — these are architectural decisions recorded in ADR-002.

**Open Questions:**
None.

---

## Architecture Impact Note (from processing boundary session, 2026-03-22)

**What the model now means for this story:**

The scenarios are correct but underspecified in two respects:

**1. Error response granularity.** The scenarios state that "the rejection states that
the effective datetime 2026-02-01T00:00:00Z falls within a closed period." With the
processing boundary model now defined, the rejection must be more specific: it must
identify which property (or properties) violated which boundary, because a submission
can contain properties from different boundaries, each validated independently. The
acceptance criteria should be revised to read approximately: the rejection identifies
the feature property (or properties) for which the business date of the submitted
effective datetime is in the GLOBAL boundary's closed-date set, and states that
the business date is closed under the GLOBAL boundary.

**2. GLOBAL boundary is now explicit.** The Given "the period containing
2026-02-01T00:00:00Z is a closed period" now means specifically: the GLOBAL boundary's
closed-date set contains the business date 2026-02-01. This is the correct and precise
reading. The scenario Given can be left as-is if "closed period" is understood as a
GLOBAL-boundary closure, but it would benefit from a clarifying note that the test
infrastructure must close the GLOBAL boundary for 2026-02-01 to establish this
precondition — not a generic "closed period" mechanism.

**New scenarios required before TDD begins:**

**Scenario: Mixed submission where one property's effective datetime is in a closed
period and another's is not.**

When a submission contains a property whose effective datetime is in the GLOBAL
boundary's closed-date set alongside a property whose effective datetime is not, the
entire submission is rejected, the rejection identifies only the property whose
effective datetime is closed, and no writes are issued. This scenario is the
per-property validation model expressed as an acceptance criterion.

**Scenario: `PROSPECTIVE_ONLY` property submitted with a past effective datetime is rejected.**

*Note: this scenario requires the `fixedTerm` feature to be implemented and should
be deferred until that feature story is scoped. It is recorded here as a known
requirement.*

When a submission contains a `PROSPECTIVE_ONLY` property (e.g. `fixedTerm.termPeriod`)
with an effective datetime that precedes the current wall-clock time, the submission is
rejected with an error identifying the property, the `PROSPECTIVE_ONLY` constraint, the
submitted effective datetime, and the wall-clock time at validation. No write is issued.
This is distinct from a closed-period rejection: `PROSPECTIVE_ONLY` violations do not
reference a boundary name; they reference the `PROSPECTIVE_ONLY` constraint directly.

**No other acceptance criteria changes are required** for the scenarios as currently
written; they are correct for the properties and boundary they test. The revisions
above are clarifications and extensions, not corrections.