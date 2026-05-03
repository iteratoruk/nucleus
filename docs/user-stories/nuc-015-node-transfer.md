## NUC-015: Transfer an account from one parameter node to another

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to move an `OPEN` account from its current parameter node to a different existing parameter node on the same ledger side,
so that I can re-classify an account whose product positioning has changed — without closing and reopening it — while retaining its identity, history, and account-level overrides.

**Background:**
A node transfer changes the parameter node to which an account is attached. The account's identity, account-level parameter values, ledger entries, and event history are preserved. The destination must be an existing parameter node on the same ledger side as the origin: transfers do not auto-create the destination, and they cannot move the account from one ledger side to another. Transfers are permitted only on `OPEN` accounts; they are rejected on `PENDING_CLOSURE` and `CLOSED`. The account's resolved feature configuration may change as a consequence of the transfer, including the resolved accounting code; that change is the configurer's responsibility to anticipate. `AccountTransferred` is emitted on success and carries the account identifier, the origin and destination classification codes, and the transfer timestamp.

**Scenarios:**

### Scenario: An OPEN account is transferred between two existing nodes on the same ledger side

```gherkin
Given an account exists with status OPEN attached to the parameter node for classification code "LIAB_INAS_2026"
And a parameter node exists for classification code "LIAB_INAS_2027"
When Cameron transfers that account from "LIAB_INAS_2026" to "LIAB_INAS_2027"
Then the transfer is accepted
And the account is attached to the parameter node for classification code "LIAB_INAS_2027"
And AccountTransferred is emitted carrying the account identifier, origin classification code "LIAB_INAS_2026", destination classification code "LIAB_INAS_2027", and the transfer timestamp
```

### Scenario: A transfer that would change the ledger side is rejected

```gherkin
Given an account exists with status OPEN attached to the parameter node for classification code "LIAB_INAS_2026"
And a parameter node exists for classification code "ASST_LEND_UNSE"
When Cameron attempts to transfer that account from "LIAB_INAS_2026" to "ASST_LEND_UNSE"
Then the transfer is rejected
And the rejection states that the destination's ledger side ASST does not match the account's ledger side LIAB
And the account remains attached to the parameter node for classification code "LIAB_INAS_2026"
And no AccountTransferred event is emitted
```

### Scenario: A transfer to a non-existent destination node is rejected

```gherkin
Given an account exists with status OPEN attached to the parameter node for classification code "LIAB_INAS_2026"
And no parameter node exists for classification code "LIAB_INAS_2099"
When Cameron attempts to transfer that account from "LIAB_INAS_2026" to "LIAB_INAS_2099"
Then the transfer is rejected
And the rejection states that the destination node "LIAB_INAS_2099" does not exist
And the account remains attached to the parameter node for classification code "LIAB_INAS_2026"
And no AccountTransferred event is emitted
And no parameter node is created for "LIAB_INAS_2099"
```

### Scenario: A transfer of a non-OPEN account is rejected

```gherkin
Given an account exists with status PENDING_CLOSURE attached to the parameter node for classification code "LIAB_INAS_2026"
And a parameter node exists for classification code "LIAB_INAS_2027"
When Cameron attempts to transfer that account from "LIAB_INAS_2026" to "LIAB_INAS_2027"
Then the transfer is rejected
And the rejection states that transfers are not permitted on a PENDING_CLOSURE account
And the account remains attached to the parameter node for classification code "LIAB_INAS_2026"
And no AccountTransferred event is emitted
```

### Scenario: A transfer carries the destination's resolved accounting code on the event

```gherkin
Given an account exists with status OPEN attached to the parameter node for classification code "LIAB_INAS_2026"
And the resolved accounting code for that account at "LIAB_INAS_2026" is "LIAB_RETL_SAVE"
And a parameter node exists for classification code "LIAB_INAS_2027"
And the resolved accounting code for any account at "LIAB_INAS_2027" is "LIAB_RETL_ISAS"
When Cameron transfers that account from "LIAB_INAS_2026" to "LIAB_INAS_2027"
Then AccountTransferred is emitted carrying the resolved accounting code "LIAB_RETL_ISAS" applicable to the account at the destination node
```

**Out of Scope:**
- Auto-creation of the destination node — opening is the only operation that auto-creates classification nodes; transfer does not. See NUC-011.
- The behaviour of in-flight Account Servicing processing across the transfer — that consumer's response to `AccountTransferred` is the concern of the Account Servicing context.
- Migration of ledger positions accumulated under the prior accounting code to the new accounting code — that is not a routine operation and is explicitly excluded from the transfer flow.
- Account-level parameter values across the transfer — they are preserved unchanged per ADR-003. The preservation invariant is established in the Parameter Value Hierarchy model and is not re-asserted as a scenario here.

**Open Questions:**

None. `AccountTransferred` carries the resolved accounting code at the destination as a first-class field. This is consistent with the configurer-persona principle that an event which requires a secondary query to be useful is incomplete, and with the Ledger context's requirement to react to attachment changes without a follow-up resolution call. The fifth scenario above asserts this contract directly.