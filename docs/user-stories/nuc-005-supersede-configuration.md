## NUC-005: Supersede account feature configuration for an existing effective date

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to submit revised account feature configuration for an effective date I have already configured at a classification code,
so that I can correct a previously submitted value without introducing a new effective date, while retaining a complete record of what was submitted and when.

**Background:**
A write for an existing (node, key, effective date) triple is a supersession. The prior value is not destroyed — it is moved to the write audit trail with its original write timestamp. The newly submitted value becomes the active value for that (key, effective date) pair. The domain model requires the write audit trail to be append-only and the active value for any triple to always be the most recently submitted value.

**Scenarios:**

### Scenario: A revised feature value supersedes the previously active value for the same effective date

```gherkin
Given a parameter node exists for classification code "SAVE_INAS_2026"
And account feature configuration for that node includes feature "F" with active value "V1" at effective date 2026-04-01
When Cameron submits account feature configuration for classification code "SAVE_INAS_2026" with feature "F" set to value "V2" and effective date 2026-04-01
Then the submission is accepted
And the applicable value of feature "F" for classification code "SAVE_INAS_2026" at resolution date 2026-04-01 is "V2"
```

**Out of Scope:**
- Supersession of account-level parameter values — those are not set via this endpoint.
- The behaviour of in-flight Account Servicing processing that may already have resolved "V1" before the supersession was submitted — that is outside this bounded context.
- Supersession of a value at an effective date in a closed period — that is rejected, covered in NUC-007.

**Open Questions:**
None.