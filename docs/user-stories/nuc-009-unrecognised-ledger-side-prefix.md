## NUC-009: Reject account feature configuration targeting a classification code with an unrecognised ledger-side prefix

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want Nucleus to reject account feature configuration submitted against a classification code whose first segment is not a recognised ledger-side value,
so that I receive a structured, actionable error at submission time rather than an opaque server failure that gives me no basis for remediation.

**Background:**
The ledger side is a closed two-value enumeration — `ASST` (asset) and `LIAB` (liability) — as defined in ADR-011. It is the one element of the classification code from which Nucleus explicitly infers semantics: it determines which features are valid for the submission and governs how the account is positioned on the ledger. A classification code whose first segment is not one of these two values cannot be associated with any recognised ledger side; any account opened against it would carry no meaningful ledger-side identity, and no feature applicability check could be performed.

A classification code can be structurally well-formed — each segment meeting the 4-character uppercase alphanumeric constraint — while still carrying an unrecognised first segment. This is a semantic validation failure, distinct from the structural format failure covered by NUC-003 Scenario 3. The validation gap is in the ledger-side lookup, not the segment format check.

**Scenarios:**

### Scenario: A submission targeting a classification code with an unrecognised ledger-side prefix is rejected

```gherkin
When Cameron submits account feature configuration for classification code "XXXX_INAS_2026"
Then the submission is rejected
And the rejection identifies "XXXX" as an unrecognised ledger-side prefix
And the rejection states that the first segment of the classification code must be a recognised ledger-side value
And no parameter node is created or modified
```

**Out of Scope:**
- Validation of whether the classification code as a whole refers to an existing node. An unrecognised but structurally valid code that carries a recognised ledger-side prefix may legitimately target a node that does not yet exist — node creation on first write is covered by NUC-001. The check in this story is solely whether the first segment identifies a known ledger side.
- Any change to the general handling of `IllegalArgumentException` in `ErrorHandler`. The fix is targeted at closing the ledger-side validation gap — ensuring the unrecognised prefix is caught and reported as a structured `NucleusValidationException` before `ClassificationCode.ledgerSide` is called — not at broadening the exception handler to absorb unrelated unhandled exceptions.
- Validation of whether the remaining segments of the classification code are semantically meaningful. Nucleus does not infer meaning from segments beyond the first.

**Open Questions:**
None.