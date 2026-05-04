## NUC-010: Open an account against an existing classification node

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to open an account against a classification code whose parameter node and resolved feature configuration are already in place,
so that I can begin a customer journey synchronously, knowing that the account exists, is correctly attached to the configuration I previously registered, and has been confirmed as such before I respond to the customer.

**Background:**
Account opening is a synchronous operation. The configurer submits a classification code and a stakeholder identifier, optionally accompanied by feature configuration to be written at the classification node or at the account level, and an idempotency key. Nucleus assigns a UUID to the new account, attaches it to the classification node, resolves the feature configuration for the account at the opening timestamp, and records the opening as a single atomic fact. The opening response carries the assigned account identifier and confirms the account is `OPEN`. `AccountOpened` is emitted on success.

This story covers the simplest case: the classification node already exists, the resolved feature configuration validates, the configurer supplies no inline configuration overrides, and the accounting code resolves to a value consistent with the account's ledger side.

**Scenarios:**

### Scenario: An account is opened against an existing classification node with valid resolved configuration

```gherkin
Given a parameter node exists for classification code "LIAB_INAS_2026"
And the resolved feature configuration for any account at "LIAB_INAS_2026" is valid against the Account Feature Catalogue
And the resolved accounting code for any account at "LIAB_INAS_2026" is "LIAB_RETL_SAVE"
When Cameron submits an account opening request against classification code "LIAB_INAS_2026" for stakeholder identifier "STK-1001" with idempotency key "IK-A-001"
Then the request is accepted synchronously
And the response carries an account identifier
And the account's status is OPEN
And the account is attached to the parameter node for classification code "LIAB_INAS_2026"
And the account's ledger side is LIAB
```

### Scenario: AccountOpened is emitted on successful opening

```gherkin
Given a parameter node exists for classification code "LIAB_INAS_2026"
And the resolved feature configuration for any account at "LIAB_INAS_2026" is valid against the Account Feature Catalogue
And the resolved accounting code for any account at "LIAB_INAS_2026" is "LIAB_RETL_SAVE"
When Cameron successfully opens an account against classification code "LIAB_INAS_2026" for stakeholder identifier "STK-1001"
Then an AccountOpened event is emitted carrying the assigned account identifier, stakeholder identifier "STK-1001", ledger side LIAB, classification code "LIAB_INAS_2026", accounting code "LIAB_RETL_SAVE", the opening timestamp, and Cameron's configurer attribution
```

### Scenario: Re-submission with the same idempotency key returns the original opening response

```gherkin
Given Cameron has successfully opened an account against classification code "LIAB_INAS_2026" for stakeholder identifier "STK-1001" with idempotency key "IK-A-001" and was assigned account identifier "A-1"
When Cameron re-submits an account opening request against classification code "LIAB_INAS_2026" for stakeholder identifier "STK-1001" with idempotency key "IK-A-001"
Then the request is accepted
And the response carries account identifier "A-1"
And no second account is created
And no second AccountOpened event is emitted
```

**Out of Scope:**
- Auto-creation of the classification node and intermediate ancestors when they do not already exist — covered in NUC-011.
- Submission of inline classification-node or account-level feature configuration as part of the opening request — covered in NUC-013 (validation) and in subsequent stories that exercise the override semantics directly.
- Validation failures for invalid resolved configuration, unresolvable accounting code, ledger-side mismatch on the resolved accounting code, or malformed classification code — covered in NUC-012 and NUC-013.
- The semantics of any opening participant beyond the Account aggregate's own work (e.g. internal accounting feature provisioning) — those depend on contributing contexts and personas not yet defined.
- The contract of the opening response payload beyond the account identifier and status — its full shape is to be settled at implementation time and is not material to this story.

**Open Questions:**
None.

**Technical Implementation Notes:**

Recorded during implementation as items that are intentionally deferred but worth
revisiting in subsequent stories.

- *Accounting code rejection branch.* `AccountService.open` raises
  `error("Accounting code unresolved for $classificationCode")` when the resolver
  returns null. This is a deliberate placeholder for the rejection path and not
  the production-quality error contract. NUC-013 covers the failure cases for
  Invariant 9 (resolvable accounting code, ledger-side consistency) and is the
  natural place to replace the `error(...)` with a structured
  `NucleusValidationException` per ADR-013.

- *Test event collector and cumulative reads.* The test infrastructure's
  `TestOutboundEventCollector` creates an on-demand consumer per call, seeks to
  the beginning of each topic, and returns every event that has ever been
  published to that topic during the test run. This was chosen for robustness on
  clean broker startup (the initial `@KafkaListener` design suffered from a
  topic-creation / consumer-subscription race that produced flaky first-run
  failures). The trade-off is that tests asserting event counts must filter by a
  test-specific marker — `accountIdentifier` is the natural one for
  `AccountOpened` — to scope assertions to events the test itself caused. If the
  filter pattern becomes repetitive or per-test event volume grows, replacing the
  cumulative read with a `@BeforeEach` snapshot of partition end-offsets and
  reading from the snapshot would localise the change to the collector class
  with no test-side impact.