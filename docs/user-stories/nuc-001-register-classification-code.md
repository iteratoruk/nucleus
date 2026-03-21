## NUC-001: Register a classification code with account feature configuration

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to submit account feature configuration for a new classification code,
so that Nucleus holds the correct behavioural rules for that product family and I can open accounts against it with confidence that the configuration I defined will govern them.

**Background:**
The ledger side — the first segment of the classification code — determines which catalogue features are valid for the submission. Configuration is written at a specific effective datetime; the submitted values govern resolution only for resolution datetimes on or after that effective datetime.

**Scenarios:**

### Scenario: A new classification code is registered with account feature configuration

```gherkin
Given a parameter node exists for classification code "SAVE"
And no parameter node exists for classification code "SAVE_INAS"
When Cameron submits account feature configuration for classification code "SAVE_INAS" with liabilityInterest enabled at a rate of 0.0350000 and an effective datetime of 2026-04-01T00:00:00Z
Then the submission is accepted
And a parameter node exists for classification code "SAVE_INAS"
And the submitted account features are the applicable configuration for classification code "SAVE_INAS" for any resolution datetime on or after 2026-04-01T00:00:00Z
```

**Out of Scope:**
- Registration where intermediate ancestor nodes do not yet exist — that is covered in NUC-002.
- Registration with an invalid classification code format or invalid feature values — that is covered in NUC-003.
- The behaviour of resolution for resolution datetimes before 2026-04-01T00:00:00Z — covered in NUC-006.
- Ledger-side enforcement: validation that the submitted features are applicable to the ledger side of the classification code is out of scope for this story.

**Open Questions:**
None.

---

## Technical Questions

**TQ-0: Package structure and bounded context boundaries.**
During the NUC-001 implementation session, the top-level package structure was established and
the rationale recorded here as a candidate for a future ADR.

The package structure follows bounded context boundaries, not feature groupings. Each top-level
package under `iterator.nucleus` corresponds to a distinct bounded context or a cohesive
infrastructure concern. The following structure is anticipated:

- `iterator.nucleus.accountfeatures` — the Account Feature Catalogue bounded context. Owns the
  external API contract (`PUT /account-features/{classificationCode}`,
  `GET /account-features/{classificationCode}`), the feature catalogue definitions, ledger-side
  applicability, and the translation layer between the typed external feature representation and
  the internal parameter key-value model. Depends on `parameters`. Has no dependents (pure
  ingress boundary).

- `iterator.nucleus.parameters` — the Parameter Configuration bounded context. Owns the
  classification code tree, parameter node aggregates, parameter values, temporal history, and
  the resolution function. Depends on nothing internal. Is depended on by `accountfeatures`,
  `accounts`, and `processing`.

- `iterator.nucleus.accounts` (anticipated) — governs Account Node Attachment lifecycle and the
  future account API. Handles `AccountOpened`/`AccountClosed` event consumption that creates and
  seals node attachment records. Depends on `parameters`. See also TQ-1 regarding the placement
  of Account Node Attachment.

- `iterator.nucleus.processing` (anticipated) — scheduled financial processing (e.g. interest
  accrual). Receives a resolution datetime and account identifier, resolves parameter values
  directly from `parameters` using parameter keys derived from the feature catalogue naming
  convention. Does not depend on `accountfeatures` at runtime — the shared naming convention
  (`liabilityInterest.interestRate` etc.) is a definition-time contract, not a runtime
  dependency. Depends on `parameters` and `accounts`.

The dependency graph contains no cycles:

```
accountfeatures → parameters
accounts        → parameters
processing      → parameters
processing      → accounts
```

The key design principle that motivated this structure: `accountfeatures` and `processing` are
independent of each other. The feature catalogue defines the parameter key namespace at
definition time; the resolution consumer (processing) uses those keys directly without going
through the catalogue layer at runtime. A single point of failure or coupling between the write
path (accountfeatures) and the execution path (processing) would be a design smell.

The question of whether to nest packages (e.g. `account.features`, `account.servicing`) was
considered and rejected. Creating a parent `account` package would imply a cohesion that does
not exist at the domain level — `account.features` and `account.servicing` would have different
collaborators and different dependency directions, making the parent a container rather than a
boundary. Flat compound package names (`accountfeatures`) are preferred.

An ADR should be recorded once the anticipated packages begin to be implemented, capturing this
structure, the dependency rules, and the rationale for any deviations.

**TQ-1: Account Node Attachment package placement.**
The Account Node Attachment aggregate is currently assigned to the Parameter Configuration
bounded context in `docs/architecture/parameter-value-hierarchy.md`. During the NUC-001
implementation session, the question arose whether it belongs in an `iterator.nucleus.parameters`
package (following the architecture doc's context assignment) or in a future
`iterator.nucleus.accounts` package (on the grounds that it governs an account's relationship
to a node, and will be a close collaborator of the account lifecycle context). The current
implementation places it in `parameters` per the architecture doc. This should be revisited
in the architecture session that defines the Account Node Attachment aggregate in code — before
any account lifecycle work begins.

**TQ-2: Scenario classification code values vs. implementation values.**
The scenario uses `SAVE` and `SAVE_INAS` as example classification codes. The implementation
uses `LIAB` and `LIAB_INAS`, reflecting the decision that the first segment of a classification
code is the ledger side expressed directly as one of two typed values: `ASST` (asset) or `LIAB`
(liability). The scenario values are provisional and were written before this decision was made.
An ADR should be recorded for the ledger side enum decision (superseding the provisional approach
in `docs/architecture/account-features.md` where `SAVE`, `LEND`, and `MORT` are named as
examples). Future stories should adopt `ASST`/`LIAB` as classification code prefixes. The
scenario values can be updated once the ADR is in place.

**TQ-5: REST API documentation deferred to a dedicated user story.**
During the NUC-001 implementation session, the question of how to document Nucleus REST API
endpoints was raised. The options considered were SpringDoc OpenAPI (with Swagger UI) and Spring
REST Docs. The identified stakeholder is Cameron the Configurer, who would use the documentation
when working against a running Nucleus instance in a non-production environment. Because the
stakeholder and their need are now explicit, this is a candidate for a formal user story rather
than an incidental technical decision. No documentation tooling has been added; the question is
deferred until that story is written and prioritised.

**TQ-4: `clean.sql` must be updated whenever a new application table is created.**
All tests that extend `AbstractApiTest` run `src/test/resources/clean.sql` before each test
method via `@Sql("/clean.sql")`. The script truncates every application table in FK-dependency
order so that all tests start from a clean state. Quartz schema tables are intentionally
excluded, as state held there is unlikely to affect test assertions. Whenever a Flyway migration
creates a new application table, the corresponding `TRUNCATE` statement must be added to
`clean.sql` in the correct position relative to any FK dependencies. There is no compile-time
enforcement of this invariant — it is a developer convention. Failure to maintain it will cause
silent state leakage between tests rather than an immediate build failure.

**TQ-3: PUT response constructed from submitted values, not from a resolution walk.**
The current implementation of `PUT /api/v1/account-features/{classificationCode}` builds the
response by echoing back the submitted feature values from the in-memory request map, rather
than by performing a resolution walk over the stored parameter values. The architecture
specifies that the response should be the resolved configuration as of the submitted effective
datetime, traversing the node hierarchy. For NUC-001 (a new node with no ancestor configuration
that would interfere), the two are equivalent. The correct behaviour — constructing the response
from stored and resolved values — requires the resolution query path, which is covered by the
GET endpoint story (ADR-007). This should be addressed at the same time as that story.