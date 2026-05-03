## NUC-012: Reject account opening when the submitted feature configuration is invalid

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want Nucleus to reject an account opening request whose accompanying feature configuration fails validation, with structured per-property errors,
so that I can resolve the configuration mistake before the customer journey proceeds, rather than discovering at first servicing that the account is misconfigured.

**Background:**
Account opening accepts feature configuration submitted at the classification node, at the account level, or both. That configuration is validated against the Account Feature Catalogue before the account is created: feature names must be recognised, ledger-side applicability must hold against the classification code's first segment, property values must satisfy their declared types and value constraints, and openness constraints must hold against the submitted effective datetime. Validation is exhaustive: all violations across all properties of the submission are collected and reported together. Any violation rejects the entire opening — no node is created or modified, no parameter value is partially applied, and no account record is created.

This story covers the validation that operates on the submitted configuration in isolation: the same validation surface that `PUT /account-features/{classificationCode}` already enforces, applied within the opening composite. Validation against the resolved configuration map — completeness, conditional inter-property requirements, accounting code resolvability and consistency — is covered separately in NUC-013.

**Scenarios:**

### Scenario: An opening request whose submitted feature is not applicable to the ledger side is rejected

```gherkin
Given a parameter node exists for classification code "LIAB_INAS_2026"
And a specific account feature is only valid for asset-side accounts
When Cameron submits an account opening request against classification code "LIAB_INAS_2026" with inline configuration that includes the asset-side-only feature
Then the request is rejected
And the rejection identifies the feature by name and states that it is not applicable to the LIAB ledger side
And no account is created
And no AccountOpened event is emitted
```

### Scenario: An opening request whose submitted property value fails type validation is rejected

```gherkin
Given a parameter node exists for classification code "LIAB_INAS_2026"
And a specific account feature property expects a value of a declared type
When Cameron submits an account opening request against classification code "LIAB_INAS_2026" with inline configuration setting that property to a value that does not conform to its declared type
Then the request is rejected
And the rejection identifies the offending property and states why the submitted value is invalid
And no account is created
```

### Scenario: An opening request whose submitted property violates an openness category is rejected

```gherkin
Given a parameter node exists for classification code "LIAB_INAS_2026"
And the BUSINESS_DAY_CLOSE boundary has a most recent closure timestamp of 2026-02-28T23:59:59Z
When Cameron submits an account opening request against classification code "LIAB_INAS_2026" with inline configuration setting liabilityInterest.interestRate to "0.0350000" at effective datetime 2026-02-01T00:00:00Z
Then the request is rejected
And the rejection identifies liabilityInterest.interestRate as the property whose business date 2026-02-01 is closed under the BUSINESS_DAY_CLOSE boundary
And no account is created
```

### Scenario: An opening request against a malformed classification code is rejected

```gherkin
When Cameron submits an account opening request against a classification code that does not consist of 4-character uppercase alphanumeric segments delimited by underscores
Then the request is rejected
And the rejection identifies the structural violation in the classification code
And no account is created
```

### Scenario: An opening request whose submitted configuration contains multiple violations reports them all

```gherkin
Given a parameter node exists for classification code "LIAB_INAS_2026"
When Cameron submits an account opening request against classification code "LIAB_INAS_2026" with inline configuration that contains two distinct property violations
Then the request is rejected
And the rejection identifies both offending properties by name
And the rejection states the reason for each violation
And no account is created
And no parameter value is written for either property
```

**Out of Scope:**
- Rejection on the basis of the resolved configuration map (completeness, conditional inter-property requirements, accounting code resolvability, ledger-side mismatch on the resolved accounting code) — covered in NUC-013.
- Re-statement of the validation contract enforced by `PUT /account-features/{classificationCode}` — that contract is established in NUC-003. This story asserts that the same validation applies inside the opening composite, with the additional consequence that rejection prevents account creation.
- Idempotency behaviour on a rejected opening — a rejected submission is not idempotent; the subsequent retry of a corrected submission is a fresh attempt under whatever idempotency key the configurer chooses.

**Open Questions:**
None.