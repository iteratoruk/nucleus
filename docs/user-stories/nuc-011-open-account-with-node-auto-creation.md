## NUC-011: Open an account against a classification code whose node and ancestors do not yet exist

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to open an account against a classification code whose parameter node has not yet been registered, supplying the feature configuration the new node requires in the same request,
so that I can introduce a new product tranche through the act of opening its first account, without a separate registration step that adds latency and a window in which a partial configuration could exist.

**Background:**
Account opening is the moment a new account classification first manifests in the system. If the classification code is not yet registered as a parameter node, the opening operation creates the node and any missing ancestor nodes as part of the same atomic transaction that creates the account. This is a deliberate point of difference from node transfer, which presupposes an existing destination and does not auto-create. The opening submission carries the feature configuration the new node requires; the configuration is written at the classification node and resolved at the opening timestamp before the account is committed. Either the entire composite — node creation, configuration write, account creation, and event emission — succeeds, or none of it does.

**Scenarios:**

### Scenario: A new classification node and its missing ancestors are created atomically with the account

```gherkin
Given a parameter node exists for classification code "LIAB"
And no parameter node exists for classification code "LIAB_INAS"
And no parameter node exists for classification code "LIAB_INAS_2026"
When Cameron submits an account opening request against classification code "LIAB_INAS_2026" for stakeholder identifier "STK-1001", supplying account feature configuration sufficient for the resolved configuration to be valid and for the accounting code to resolve consistently with the LIAB ledger side
Then the request is accepted synchronously
And a parameter node exists for classification code "LIAB_INAS"
And a parameter node exists for classification code "LIAB_INAS_2026"
And an account exists with status OPEN attached to the parameter node for classification code "LIAB_INAS_2026"
And AccountOpened is emitted carrying the assigned account identifier and classification code "LIAB_INAS_2026"
```

### Scenario: Failure of validation during opening leaves no node, no configuration, and no account behind

```gherkin
Given a parameter node exists for classification code "LIAB"
And no parameter node exists for classification code "LIAB_INAS"
And no parameter node exists for classification code "LIAB_INAS_2026"
When Cameron submits an account opening request against classification code "LIAB_INAS_2026" whose accompanying account feature configuration causes the resolved configuration to be invalid
Then the request is rejected
And no parameter node exists for classification code "LIAB_INAS"
And no parameter node exists for classification code "LIAB_INAS_2026"
And no account is created
And no AccountOpened event is emitted
```

**Out of Scope:**
- The shape of the structured validation error returned on rejection — covered in NUC-012 and NUC-013, which are the validation-failure stories. This story asserts only that the side effects of opening do not persist on rejection.
- The behaviour of subsequent writes to the auto-created intermediate nodes — those nodes are valid empty parameter nodes and their subsequent configuration is governed by the existing account-features API behaviour established in NUC-001 and NUC-002.
- Auto-creation of the destination node on node transfer — transfer does not auto-create. See NUC-015.

**Open Questions:**
None.