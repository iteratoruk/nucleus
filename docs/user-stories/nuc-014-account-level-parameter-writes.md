## NUC-014: Write account-level parameter values to govern a single account

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to write parameter values at the account level on an `OPEN` account,
so that I can express configuration that applies to a single account in deviation from the configuration resolved at its classification node, without having to register a more specific classification node for the sake of one account.

**Background:**
Account-level parameter values sit at the most specific level of the resolution hierarchy and take precedence over all classification-code-level values during resolution. They are subject to the same Account Feature Catalogue validation as classification-node values: type correctness, ledger-side applicability, openness compliance, and any conditional inter-property requirements that apply when the resolved map for the account is reassessed. Account-level writes are permitted only on `OPEN` accounts; they are rejected on `PENDING_CLOSURE` and `CLOSED`, consistent with the principle that closure intent freezes account attributes.

This story establishes the write surface and the lifecycle gating on it. It does not establish any inline-write surface as part of opening or any specific account-level endpoint contract beyond what the architecture already commits to: the same feature representation as the classification-node API.

**Scenarios:**

### Scenario: An account-level parameter write on an OPEN account takes effect

```gherkin
Given an account exists with status OPEN attached to the parameter node for classification code "LIAB_INAS_2026"
And the resolved value of liabilityInterest.interestRate for that account at resolution datetime 2026-04-01T00:00:00Z is "0.0350000" inherited from the classification node
When Cameron writes the account-level value of liabilityInterest.interestRate to "0.0400000" with effective datetime 2026-04-01T00:00:00Z for that account
Then the write is accepted
And the resolved value of liabilityInterest.interestRate for that account at resolution datetime 2026-04-01T00:00:00Z is "0.0400000"
```

### Scenario: An account-level parameter write on a PENDING_CLOSURE account is rejected

```gherkin
Given an account exists with status PENDING_CLOSURE
When Cameron attempts to write an account-level parameter value for that account
Then the write is rejected
And the rejection states that account-level parameter writes are not permitted on a PENDING_CLOSURE account
And the resolved configuration for that account is unchanged
```

### Scenario: An account-level parameter write on a CLOSED account is rejected

```gherkin
Given an account exists with status CLOSED
When Cameron attempts to write an account-level parameter value for that account
Then the write is rejected
And the rejection states that account-level parameter writes are not permitted on a CLOSED account
And the resolved configuration for that account is unchanged
```

### Scenario: An account-level parameter write whose value fails validation is rejected

```gherkin
Given an account exists with status OPEN attached to the parameter node for classification code "LIAB_INAS_2026"
When Cameron attempts to write an account-level parameter value whose property fails validation against the Account Feature Catalogue
Then the write is rejected
And the rejection identifies the offending property and the reason for the violation
And the resolved configuration for that account is unchanged
```

**Out of Scope:**
- The behaviour of inline account-level configuration submitted as part of an opening request — that behaviour is a property of the opening composite covered in NUC-010, NUC-012, and NUC-013.
- The behaviour of account-level parameter values across a node transfer — they are preserved unchanged per ADR-003 and OQ-2 of the Parameter Value Hierarchy model. That preservation is asserted in the node transfer story, NUC-015, not here.
- The exact endpoint contract for the account-level write API beyond the requirement that it follows the same feature representation as the classification-node API.

**Open Questions:**
None.