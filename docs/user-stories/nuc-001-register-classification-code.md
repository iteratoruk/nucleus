## NUC-001: Register a classification code with account feature configuration

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to submit account feature configuration for a new classification code,
so that Nucleus holds the correct behavioural rules for that product family and I can open accounts against it with confidence that the configuration I defined will govern them.

**Background:**
The ledger side — the first segment of the classification code — determines which catalogue features are valid for the submission. Configuration is written at a specific effective date; the submitted values govern resolution only for resolution dates on or after that effective date.

**Scenarios:**

### Scenario: A new classification code is registered with valid account feature configuration

```gherkin
Given a parameter node exists for classification code "SAVE"
And no parameter node exists for classification code "SAVE_INAS"
When Cameron submits valid account feature configuration for classification code "SAVE_INAS" with an effective date of 2026-04-01
Then the submission is accepted
And a parameter node exists for classification code "SAVE_INAS"
And the submitted account features are the applicable configuration for classification code "SAVE_INAS" for any resolution date on or after 2026-04-01
```

**Out of Scope:**
- Registration where intermediate ancestor nodes do not yet exist — that is covered in NUC-002.
- Registration with an invalid classification code format or invalid feature values — that is covered in NUC-003.
- The behaviour of resolution for resolution dates before 2026-04-01 — covered in NUC-006.

**Open Questions:**
None.