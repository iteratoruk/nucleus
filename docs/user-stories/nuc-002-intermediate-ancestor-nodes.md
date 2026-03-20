## NUC-002: Intermediate ancestor nodes are created automatically when registering a deep classification code

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to submit account feature configuration for a classification code whose intermediate ancestor nodes have not yet been explicitly registered,
so that I can configure a specific product tranche directly without first having to register every level of the hierarchy independently.

**Background:**
Classification codes carry implicit parent relationships through their structure: `SAVE_INAS_2026` is a descendant of `SAVE_INAS`, which is a descendant of `SAVE`. When a submission targets a node whose intermediate ancestors do not yet exist as parameter nodes, Nucleus creates those intermediate nodes automatically as empty nodes. An empty node is a valid node — it holds no explicit feature values of its own and participates in the resolution walk by inheriting from its own ancestors.

**Scenarios:**

### Scenario: Missing intermediate ancestors are created as empty nodes

```gherkin
Given a parameter node exists for classification code "SAVE"
And no parameter node exists for classification code "SAVE_INAS"
And no parameter node exists for classification code "SAVE_INAS_2026"
When Cameron submits valid account feature configuration for classification code "SAVE_INAS_2026" with an effective date of 2026-04-01
Then the submission is accepted
And a parameter node exists for classification code "SAVE_INAS"
And a parameter node exists for classification code "SAVE_INAS_2026"
And the parameter node for classification code "SAVE_INAS" has no explicitly configured account features
And the submitted account features are the applicable configuration for classification code "SAVE_INAS_2026" for any resolution date on or after 2026-04-01
```

**Out of Scope:**
- What Cameron can do with implicitly created intermediate nodes subsequently — those nodes are fully writable via the same endpoint.
- Resolution semantics for accounts attached to implicitly created intermediate nodes — those nodes inherit from their own ancestors in the usual way.

**Open Questions:**
None.