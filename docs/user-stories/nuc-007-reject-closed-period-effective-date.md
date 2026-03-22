## NUC-007: Reject account feature configuration with an effective datetime in a closed period

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want Nucleus to reject any account feature configuration whose effective datetime falls within a closed period,
so that the configuration basis of already-finalised financial processing is protected from retroactive change.

**Background:**
A closed period is one for which Nucleus has completed all scheduled processing and whose financial record is considered final. Setting or superseding a parameter value with an effective datetime in a closed period would alter the basis on which immutable ledger entries were produced. Nucleus enforces this boundary at write time. A submission with a past effective datetime in an open period is not subject to this restriction and is accepted as late registration.

The BUSINESS_DAY_CLOSE boundary governs effective datetime constraints for properties classified with the `BUSINESS_DAY_CLOSE` openness category. The boundary projection maintained by Parameter Configuration records the most recent closure timestamp for this boundary; it is updated when Nucleus receives a `PeriodClosed` event. When a submitted effective datetime's business date is on or before the most recent BUSINESS_DAY_CLOSE closure timestamp, the submission is rejected. The rejection error identifies the specific property in violation, the boundary name (`BUSINESS_DAY_CLOSE`), and the business date that is closed. All violations across all properties in a submission are collected and reported together; any single violation causes the entire submission to be rejected with no writes applied.

**Scenarios:**

### Scenario: A new submission with an effective datetime in a closed period is rejected

```gherkin
Given the BUSINESS_DAY_CLOSE boundary has a most recent closure timestamp of 2026-02-28T23:59:59Z
When Cameron submits account feature configuration for classification code "LIAB_INAS_2026" with liabilityInterest.interestRate set to "0.0350000" and effective datetime 2026-02-01T00:00:00Z
Then the submission is rejected
And the rejection identifies liabilityInterest.interestRate as the property whose business date 2026-02-01 is closed under the BUSINESS_DAY_CLOSE boundary
And no parameter node is created or modified
```

### Scenario: A supersession attempt for a closed-period effective datetime is rejected

```gherkin
Given the BUSINESS_DAY_CLOSE boundary has a most recent closure timestamp of 2026-02-28T23:59:59Z
And account feature configuration for classification code "LIAB_INAS_2026" has liabilityInterest.interestRate set to "0.0350000" at effective datetime 2026-02-01T00:00:00Z
When Cameron submits account feature configuration for classification code "LIAB_INAS_2026" with liabilityInterest.interestRate set to "0.0400000" and effective datetime 2026-02-01T00:00:00Z
Then the submission is rejected
And the rejection identifies liabilityInterest.interestRate as the property whose business date 2026-02-01 is closed under the BUSINESS_DAY_CLOSE boundary
And the applicable value of liabilityInterest.interestRate for classification code "LIAB_INAS_2026" at effective datetime 2026-02-01T00:00:00Z remains "0.0350000"
```

### Scenario: A mixed submission where one property's effective datetime is in a closed period is rejected in full

```gherkin
Given the BUSINESS_DAY_CLOSE boundary has a most recent closure timestamp of 2026-02-28T23:59:59Z
When Cameron submits account feature configuration for classification code "LIAB_INAS_2026" with liabilityInterest.interestRate set to "0.0350000" at effective datetime 2026-02-01T00:00:00Z and liabilityInterest.enabled set to true at effective datetime 2026-04-01T00:00:00Z
Then the submission is rejected
And the rejection identifies liabilityInterest.interestRate as the property whose business date 2026-02-01 is closed under the BUSINESS_DAY_CLOSE boundary
And the rejection does not identify liabilityInterest.enabled as a violation
And no parameter value is written for liabilityInterest.interestRate or liabilityInterest.enabled
```

**Deferred Scenario: PROSPECTIVE_ONLY violation**

The following scenario is required but is deferred until the `fixedTerm` feature is scoped and implemented. At that point it must be converted to a full Gherkin scenario and implemented as part of this story or a designated successor.

When a `PROSPECTIVE_ONLY` property (e.g. `fixedTerm.termPeriod`) is submitted with an effective datetime that precedes the current wall-clock time at validation, the submission is rejected. The rejection identifies the property name, states the `PROSPECTIVE_ONLY` constraint, and reports both the submitted effective datetime and the wall-clock time at validation. No write is issued. This is structurally distinct from a closed-period rejection: a `PROSPECTIVE_ONLY` violation references the constraint directly — not a boundary name or closure record — and the comparison is on full datetimes (UTC, to second precision), not on business dates.

**Out of Scope:**
- The definition of what constitutes period close, which context owns and signals the close event, and how Parameter Configuration enforces the boundary — these are architectural decisions recorded in ADR-002.

**Open Questions:**
None.