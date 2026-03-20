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
When Cameron submits account feature configuration for classification code "SAVE_INAS_2026" with an effective datetime of 2026-02-01T00:00:00Z
Then the submission is rejected
And the rejection states that the effective datetime 2026-02-01T00:00:00Z falls within a closed period
And no parameter node is created or modified
```

### Scenario: A supersession attempt for a closed-period effective datetime is rejected

```gherkin
Given the period containing 2026-02-01T00:00:00Z is a closed period
And account feature configuration for classification code "SAVE_INAS_2026" includes feature "F" with active value "V1" at effective datetime 2026-02-01T00:00:00Z
When Cameron submits account feature configuration for classification code "SAVE_INAS_2026" with feature "F" set to value "V2" and effective datetime 2026-02-01T00:00:00Z
Then the submission is rejected
And the rejection states that the effective datetime 2026-02-01T00:00:00Z falls within a closed period
And the applicable value of feature "F" for classification code "SAVE_INAS_2026" at effective datetime 2026-02-01T00:00:00Z remains "V1"
```

**Out of Scope:**
- The definition of what constitutes period close, which context owns and signals the close event, and how Parameter Configuration enforces the boundary — these are architectural decisions recorded in ADR-002.

**Open Questions:**
None.