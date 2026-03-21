## NUC-001: Register a classification code with account feature configuration

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to submit account feature configuration for a new classification code,
so that Nucleus holds the correct behavioural rules for that product family and I can open accounts against it with confidence that the configuration I defined will govern them.

**Background:**
The ledger side — the first segment of the classification code — determines which catalogue features are valid for the submission. Configuration is written at a specific effective datetime; the submitted values govern resolution only for resolution datetimes on or after that effective datetime.

**Scenarios:**

### Scenario: A new classification code is registered with account feature configuration

```gherkin
Given a parameter node exists for classification code "SAVE"
And no parameter node exists for classification code "SAVE_INAS"
When Cameron submits account feature configuration for classification code "SAVE_INAS" with liabilityInterest enabled at a rate of 0.0350000 and an effective datetime of 2026-04-01T00:00:00Z
Then the submission is accepted
And a parameter node exists for classification code "SAVE_INAS"
And the submitted account features are the applicable configuration for classification code "SAVE_INAS" for any resolution datetime on or after 2026-04-01T00:00:00Z
```

**Out of Scope:**
- Registration where intermediate ancestor nodes do not yet exist — that is covered in NUC-002.
- Registration with an invalid classification code format or invalid feature values — that is covered in NUC-003.
- The behaviour of resolution for resolution datetimes before 2026-04-01T00:00:00Z — covered in NUC-006.
- Ledger-side enforcement: validation that the submitted features are applicable to the ledger side of the classification code is out of scope for this story.

**Open Questions:**
None.