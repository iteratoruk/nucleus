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
When Cameron submits account feature configuration for classification code "LIAB_INAS" that includes the asset-side-only feature
Then the submission is rejected
And the rejection identifies the invalid feature by name
And the rejection states that the feature is not applicable to the "LIAB" ledger side
And no parameter node is created or modified
```

### Scenario: A submission containing a feature value that fails type validation is rejected

```gherkin
Given a specific account feature expects a value of a declared type
When Cameron submits account feature configuration for classification code "LIAB_INAS" where that feature carries a value that does not conform to its declared type
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
Given account feature configuration is submitted for classification code "LIAB_INAS" with two features that each fail validation for different reasons
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

---

## Technical Notes

**`NucleusViolation.subject` — not `feature`.** The violation type in the structured error response uses `subject` rather than `feature` as the field name. Scenario 3 (malformed classification code) produces a violation whose subject is the classification code itself, not a feature name. Using `feature` would conflate two distinct kinds of violation subject. `subject` is general enough to hold a feature name (Scenarios 1, 2, 4) or a classification code identifier (Scenario 3) without overloading the semantics of the field.

**Exception ownership and error handler centralisation — ADR-013.** The initial implementation placed `FeatureConfigurationException` and its `@ControllerAdvice` handler in the `accountfeatures` package to avoid a circular import with the root `ErrorHandler`. During the NUC-003 refactoring pass, this was resolved by defining `NucleusValidationException` in the root package and consolidating all exception-to-HTTP translation into the single root `ErrorHandler`. The principle: exception types used to produce HTTP responses are root-owned infrastructure; sub-packages are conformist and throw root-defined types. See ADR-013.

**TDD ordering slip — Scenario 4.** Scenario 4 passed immediately on its first test run without requiring new production code. The exhaustive violation collection structure — combining `ledgerSideApplicabilityViolations` and `propertyConstraintViolations` before throwing — was introduced while implementing Scenario 2, not driven by Scenario 4's failing test. This is a YAGNI violation: the combination anticipated Scenario 4 rather than being forced by it. The correct ordering would have been to implement Scenario 2 with only a single violation type, then introduce the combination when Scenario 4's test demanded it. Noted as a discipline failure, not a correctness failure — the implementation is right, the sequence was not.
