## NUC-013: Reject account opening when the resolved configuration is incomplete or inconsistent

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want Nucleus to reject an account opening request whose resolved feature configuration is incomplete or inconsistent — even where each individually submitted value is valid in isolation — with structured per-property errors,
so that an account whose servicing or accounting position would be incorrectly determined never comes into existence, and so I can correct the configuration at the classification node or at the account level before reattempting.

**Background:**
At opening, Nucleus resolves the feature configuration for the account-to-be: account-level overrides over classification-node-resolved values, with catalogue-declared defaults applied where present. The resolved map is then validated against the Account Feature Catalogue. Validity here subsumes completeness and includes conditional inter-property requirements: a feature definition may declare that one property must resolve to a value when another resolves to a particular state. Validity also requires that an accounting code is resolvable for the account and that its first segment equals the account's ledger side. Any failure rejects the opening with structured per-property errors and no side effects persist.

The validation in this story operates on the resolved map, not on the submitted values in isolation. A configuration that passes the per-property checks of NUC-012 may still fail here because resolution leaves a required property absent, because two resolved properties are inconsistent under a conditional rule, or because the accounting code resolution walks to no value or to a value of the wrong ledger side.

**Scenarios:**

### Scenario: Opening is rejected when no accounting code resolves for the account

```gherkin
Given a parameter node exists for classification code "LIAB_INAS_2026"
And the resolved feature configuration for any account at "LIAB_INAS_2026" is otherwise valid against the Account Feature Catalogue
And no accounting code parameter value is resolvable at "LIAB_INAS_2026" or at any of its ancestors
When Cameron submits an account opening request against classification code "LIAB_INAS_2026" for stakeholder identifier "STK-1001"
Then the request is rejected
And the rejection identifies the accounting code as the property that failed to resolve
And no account is created
And no AccountOpened event is emitted
```

### Scenario: Opening is rejected when the resolved accounting code's ledger side does not match the account's ledger side

```gherkin
Given a parameter node exists for classification code "LIAB_INAS_2026"
And the resolved feature configuration for any account at "LIAB_INAS_2026" is otherwise valid against the Account Feature Catalogue
And the resolved accounting code for any account at "LIAB_INAS_2026" is "ASST_LEND_UNSE"
When Cameron submits an account opening request against classification code "LIAB_INAS_2026" for stakeholder identifier "STK-1001"
Then the request is rejected
And the rejection identifies the accounting code as inconsistent with the account's LIAB ledger side
And no account is created
```

### Scenario: Opening is rejected when a conditional inter-property requirement is unsatisfied in the resolved map

```gherkin
Given a parameter node exists for classification code "LIAB_INAS_2026"
And the Account Feature Catalogue declares that liabilityInterest.interestRate must resolve to a value when liabilityInterest.enabled resolves to true
And the resolved feature configuration for any account at "LIAB_INAS_2026" has liabilityInterest.enabled resolving to true and liabilityInterest.interestRate resolving to no value
When Cameron submits an account opening request against classification code "LIAB_INAS_2026" for stakeholder identifier "STK-1001"
Then the request is rejected
And the rejection identifies liabilityInterest.interestRate as the property required but not resolved
And the rejection identifies liabilityInterest.enabled as the property whose state imposed the requirement
And no account is created
```

### Scenario: Opening is rejected when the resolved map contains multiple distinct violations

```gherkin
Given a parameter node exists for classification code "LIAB_INAS_2026"
And the resolved feature configuration for any account at "LIAB_INAS_2026" contains two distinct violations: an unresolvable accounting code and a conditional inter-property requirement that is unsatisfied
When Cameron submits an account opening request against classification code "LIAB_INAS_2026" for stakeholder identifier "STK-1001"
Then the request is rejected
And the rejection identifies both violations by property
And the rejection states the reason for each violation
And no account is created
```

**Out of Scope:**
- Validation of the submitted configuration values in isolation — covered in NUC-012.
- The mechanism by which the catalogue declares conditional inter-property requirements and per-property defaults — that catalogue extension is the subject of ADR-029 and is a prerequisite for the conditional-completeness scenario above. The scenario describes the behaviour required of opening once that extension exists.
- The invariant that the accounting code may not be superseded under non-`CLOSED` accounts (ADR-026) — that invariant governs writes to the accounting code parameter at a node, not opening, and is a separate story concern.

**Open Questions:**

The catalogue extension that expresses conditional inter-property requirements and per-property defaults (ADR-029) is forward-looking; it is not yet defined in `docs/architecture/account-features.md`. The third scenario above depends on the catalogue being able to declare the conditional rule it asserts.

**Recommended approach for the tdd-implementor.** Treat the resolved-map validation surface as supporting injection of validators (accepting that none may be present in the current catalogue), and stub the conditional inter-property validator in tests for the time being. The injection point is in place from the outset; the canonical declared rule (`liabilityInterest.interestRate` required when `liabilityInterest.enabled` resolves to true) is exercised via a stub validator until the catalogue extension lands. This avoids deferring the scenario behind the ADR-029 catalogue work and avoids the alternative — building the validation pipeline without an extension point and re-architecting it when the first real conditional rule arrives. The same forward-compatibility discipline applied to the lifecycle participant model in NUC-019 and NUC-020 applies here.