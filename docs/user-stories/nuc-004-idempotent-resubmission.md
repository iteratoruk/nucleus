## NUC-004: Re-submission with the same idempotency ID is a no-op

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to supply an idempotency ID with each account feature configuration submission and have Nucleus honour it as the sole determinant of whether a submission has already been processed,
so that I can safely retry a submission following a network failure or uncertain response without risking an unintended change to the configuration.

**Background:**
Each submission to `PUT /account-features/{classificationCode}` carries a client-supplied idempotency ID. Idempotency IDs are globally unique and permanent: they are not scoped to a classification code or any other dimension, and they do not expire. An ID that has been accepted once may never be reused to produce a different outcome. If Nucleus has already accepted a submission carrying that ID — regardless of which classification code it targeted — any subsequent submission with the same ID is a no-op: the original configuration stands and the re-submission is accepted without reprocessing. The idempotency check is based solely on the ID — if the re-submission carries a different configuration payload or targets a different classification code, the original configuration is still preserved. The payload and target of a re-submission are not inspected.

**Scenarios:**

### Scenario: Re-submission with the same idempotency ID is accepted and the original configuration is preserved

```gherkin
Given Cameron has submitted account feature configuration for classification code "LIAB_INAS_2026" with idempotency ID "IK-001" and that submission was accepted
When Cameron re-submits account feature configuration for classification code "LIAB_INAS_2026" with idempotency ID "IK-001"
Then the submission is accepted
And the applicable configuration for classification code "LIAB_INAS_2026" is unchanged from the original submission
```

### Scenario: Re-submission with the same idempotency ID and a different payload is a no-op

```gherkin
Given Cameron has submitted account feature configuration for classification code "LIAB_INAS_2026" with idempotency ID "IK-001" and feature "F" set to value "V1", and that submission was accepted
When Cameron re-submits account feature configuration for classification code "LIAB_INAS_2026" with idempotency ID "IK-001" and feature "F" set to value "V2"
Then the submission is accepted
And the applicable value of feature "F" for classification code "LIAB_INAS_2026" remains "V1"
```

### Scenario: Re-submission with the same idempotency ID against a different classification code is also a no-op

```gherkin
Given Cameron has submitted account feature configuration for classification code "LIAB_INAS_2026" with idempotency ID "IK-001" and that submission was accepted
When Cameron submits account feature configuration for classification code "LIAB_INAS_2027" with idempotency ID "IK-001"
Then the submission is accepted
And no parameter node is created or modified for classification code "LIAB_INAS_2027"
```

**Out of Scope:**
- Submitting with a new idempotency ID to update configuration — that is supersession, covered in NUC-005.

**Open Questions:**
None. If the permanent uniqueness requirement proves operationally inconvenient — for example due to storage constraints — this should be revisited in an architecture session before any relaxation is introduced.