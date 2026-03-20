## NUC-003: Reject account feature configuration that fails validation

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want Nucleus to reject invalid account feature configuration with structured, actionable errors at the time of submission,
so that I know immediately whether my configuration is correct and can remediate it before any account is opened against a malformed or inapplicable configuration.

**Background:**
Validation occurs at write time. The errors returned must identify which features are invalid and why, so that Cameron can resolve each issue without guesswork. Silent acceptance of configuration that Nucleus cannot honour is not acceptable, as Cameron's SLA obligations to customers depend on knowing at configuration time whether the configuration is valid.

**Scenarios:**

### Scenario: A submission containing a feature not valid for the ledger side is rejected

```gherkin
Given a specific account feature is only valid for asset-side accounts
When Cameron submits account feature configuration for classification code "SAVE_INAS" that includes the asset-side-only feature
Then the submission is rejected
And the rejection identifies the invalid feature by name
And the rejection states that the feature is not applicable to the "SAVE" ledger side
And no parameter node is created or modified
```

### Scenario: A submission containing a feature value that fails type validation is rejected

```gherkin
Given a specific account feature expects a value of a declared type
When Cameron submits account feature configuration for classification code "SAVE_INAS" where that feature carries a value that does not conform to its declared type
Then the submission is rejected
And the rejection identifies the invalid feature by name
And the rejection states why the submitted value is invalid
And no parameter node is created or modified
```

### Scenario: A submission targeting a malformed classification code is rejected

```gherkin
When Cameron submits account feature configuration for a classification code that does not consist of 4-character uppercase alphanumeric segments delimited by underscores
Then the submission is rejected
And the rejection identifies the structural violation in the classification code
```

### Scenario: A submission with multiple invalid features reports all violations

```gherkin
Given account feature configuration is submitted for classification code "SAVE_INAS" with two features that each fail validation for different reasons
When the submission is processed
Then the submission is rejected
And the rejection identifies both invalid features by name
And the rejection states the reason for each individual violation
```

**Out of Scope:**
- Validation of whether the configuration is semantically valid for a product domain — for example, whether an interest rate is commercially reasonable. Nucleus validates structural and type constraints; it does not make business-intent judgements.
- Validation of configuration completeness. Nucleus does not require every possible feature to be present. Features absent from the submission are resolved from ancestor nodes.

**Open Questions:**
None.