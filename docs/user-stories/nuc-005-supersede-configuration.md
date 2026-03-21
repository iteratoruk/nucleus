## NUC-005: Supersede account feature configuration for an existing effective datetime

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to submit revised account feature configuration for an effective datetime I have already configured at a classification code,
so that I can correct a previously submitted value without introducing a new effective datetime, while retaining a complete record of what was submitted and when.

**Background:**
A write for an existing (node, key, effective datetime) triple is a supersession. The prior value is not destroyed — it is moved to the write audit trail with its original write timestamp. The newly submitted value becomes the active value for that (key, effective datetime) pair. The domain model requires the write audit trail to be append-only and the active value for any triple to always be the most recently submitted value.

**Scenarios:**

### Scenario: A revised feature value supersedes the previously active value for the same effective datetime

```gherkin
Given a parameter node exists for classification code "LIAB_INAS_2026"
And account feature configuration for that node includes feature "F" with active value "V1" at effective datetime 2026-04-01T00:00:00Z
When Cameron submits account feature configuration for classification code "LIAB_INAS_2026" with feature "F" set to value "V2" and effective datetime 2026-04-01T00:00:00Z
Then the submission is accepted
And the applicable value of feature "F" for classification code "LIAB_INAS_2026" at resolution datetime 2026-04-01T00:00:00Z is "V2"
```

**Out of Scope:**
- Supersession of account-level parameter values — those are not set via this endpoint.
- The behaviour of in-flight Account Servicing processing that may already have resolved "V1" before the supersession was submitted — that is outside this bounded context.
- Supersession of a value at an effective datetime in a closed period — that is rejected, covered in NUC-007.

**Open Questions:**
None.