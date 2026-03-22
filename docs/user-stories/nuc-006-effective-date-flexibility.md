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
Given the BUSINESS_DAY_CLOSE boundary has no closure record with a business date on or before 2026-03-01
And no account feature configuration exists for classification code "LIAB_INAS_2026"
When Cameron submits account feature configuration for classification code "LIAB_INAS_2026" with an effective datetime of 2026-03-01T00:00:00Z
Then the submission is accepted
And the submitted features are the applicable configuration for classification code "LIAB_INAS_2026" at resolution datetime 2026-03-01T00:00:00Z
And the submitted features are the applicable configuration for classification code "LIAB_INAS_2026" at resolution datetime 2026-03-20T12:00:00Z
```

**Out of Scope:**
- Configuration with an effective datetime in a closed period — that is rejected, covered in NUC-007.
- Submission of a PROSPECTIVE_ONLY property (e.g. `fixedTerm.termPeriod`) with a past effective datetime — that is a distinct form of rejection covered in NUC-007, deferred until the fixedTerm feature is scoped.

**Open Questions:**
None.