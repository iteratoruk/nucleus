## NUC-021: Query current account state to support a customer journey

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to query the current state of an account I have configured — its status, the parameter node it is attached to, its stakeholder identifier, and its resolved feature configuration —
so that I can drive the customer journey from authoritative Nucleus state without maintaining a parallel store of account state that would inevitably drift from the source of truth.

**Background:**
Configurer queries are the read-side counterpart to the synchronous open / close / transfer surface. They support customer-journey decisions: confirming that an account opening succeeded before the customer is told the product is active, retrieving the resolved configuration that explains a particular interest application or fee, displaying the current attachment after a transfer. The query surface returns current state — there is no "as at" parameter on these queries beyond the catalogue's existing `GET /account-features/{classificationCode}?asAt={date}` mechanism for resolving configuration at an arbitrary point. Account-by-account historical timeline queries are a different use case (Eddie's diagnostic surface, NUC-022).

**Scenarios:**

### Scenario: Cameron retrieves the current state of an account

```gherkin
Given an account exists with status OPEN, account identifier "A-1", stakeholder identifier "STK-1001", attached to the parameter node for classification code "LIAB_INAS_2026"
When Cameron queries the current state of account "A-1"
Then the response carries account identifier "A-1"
And the response carries status OPEN
And the response carries stakeholder identifier "STK-1001"
And the response carries the current classification code "LIAB_INAS_2026"
And the response carries the ledger side LIAB
```

### Scenario: Cameron retrieves the resolved feature configuration for an account

```gherkin
Given an account exists with account identifier "A-1" attached to the parameter node for classification code "LIAB_INAS_2026"
And the resolved value of liabilityInterest.interestRate for that account at the current resolution datetime is "0.0350000"
When Cameron queries the resolved feature configuration for account "A-1"
Then the response carries liabilityInterest.interestRate with value "0.0350000"
And the response includes account-level overrides taking precedence over classification-node-resolved values
```

### Scenario: Cameron retrieves the current node attachment for an account

```gherkin
Given an account exists with account identifier "A-1"
And account "A-1" was opened attached to the parameter node for classification code "LIAB_INAS_2026"
And account "A-1" was subsequently transferred to the parameter node for classification code "LIAB_INAS_2027"
When Cameron queries the current node attachment of account "A-1"
Then the response carries classification code "LIAB_INAS_2027"
And the response carries the attachment timestamp of the current attachment
```

### Scenario: A query for an unknown account is rejected as not found

```gherkin
Given no account exists with account identifier "A-9999"
When Cameron queries the current state of account "A-9999"
Then the query is rejected with a not-found response
And the rejection identifies "A-9999" as the account that was not found
```

**Out of Scope:**
- Historical or audit timeline queries (the lifecycle event stream, the full attachment history) — covered in NUC-022 from Eddie's diagnostic perspective; Cameron's customer-journey use case does not require these in this story.
- The precise transport (REST endpoint shapes, payload formats) — the architecture commits to query availability without prescribing endpoint structure beyond the existing patterns.
- Hypothetical-account configuration queries — `GET /account-features/{classificationCode}?asAt={date}` already serves this need (ADR-007) and is not affected by this story.
- Querying CLOSED accounts — the architecture allows this and the configurer's normal flow rarely needs it; if it is required, the same query surface returns the closed account's state, but no scenarios in this story exercise it. Eddie's broader scope (NUC-022) covers CLOSED accounts explicitly.
- Stakeholder-level query views (set membership, financial aggregates) — these are derived from the account population and are anticipated to be served by the internal accounting feature's per-stakeholder aggregation accounts. Their query surface is out of scope until the accounting feature is scoped.

**Open Questions:**

Whether a single query endpoint returns the combined account state — status, stakeholder identifier, current attachment, resolved configuration — or whether these are separate endpoints whose responses the configurer composes is an API shape question that does not affect the domain semantics. The scenarios above are written so that they can be implemented against either shape.

**Guidance for the tdd-implementor.** A single combined endpoint returning status, stakeholder identifier, current attachment, and resolved configuration is the desired shape. The N+1 aggregation cost of assembling the combined response from its underlying sources is what should ultimately arbitrate: where the cost is acceptable, the combined endpoint is the correct shape because it minimises round-trips and gives the configurer authoritative state in one call. If during implementation the aggregation cost for any constituent (most likely the resolved-configuration walk for a deep classification node) is found to be too high to support inside the combined response, that finding should be recorded against the implementation and taken into an architecture review. A dedicated projection endpoint — and a story authored for it — is the intended remedy in that case, rather than degrading the combined endpoint or quietly omitting the costly constituent.