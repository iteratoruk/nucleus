## NUC-008: Suppress inheritance of a feature by setting it to explicitly absent

**Persona:** Cameron the Configurer

**Story:**
As Cameron the Configurer,
I want to mark a specific account feature as explicitly absent at a child classification code,
so that accounts attached to that node do not inherit the feature value defined at a parent node, making the absence a deliberate configuration decision rather than a gap that resolution will silently fill.

**Background:**
The default resolution behaviour for a key not configured at a node is to walk up the hierarchy to ancestor nodes. Explicit absence terminates this walk at the node where it is set: the resolution function returns no value for that key, regardless of what ancestor nodes hold. Explicit absence is distinct from non-configuration — a key that has never been written for a node permits inheritance; a key that has been explicitly set to absent does not. An explicit absence marker may be superseded by submitting a concrete value for the same (classification code, feature, effective datetime) triple; from that point the concrete value governs resolution and inheritance suppression no longer applies for that effective datetime. The representation of explicit absence in the internal model is an implementation matter recorded in ADR-006.

**Scenarios:**

### Scenario: A feature set to explicitly absent at a child node is not inherited from the parent

```gherkin
Given a parameter node exists for classification code "LIAB" with feature "F" set to value "V" at effective datetime 2026-01-01T00:00:00Z
And a parameter node exists for classification code "LIAB_INAS"
And feature "F" is set to explicitly absent at classification code "LIAB_INAS" with an effective datetime of 2026-01-01T00:00:00Z
When the applicable value of feature "F" is resolved for classification code "LIAB_INAS" at resolution datetime 2026-04-01T00:00:00Z
Then no value is returned for feature "F"
```

### Scenario: A feature not configured at a child node inherits from the parent

```gherkin
Given a parameter node exists for classification code "LIAB" with feature "F" set to value "V" at effective datetime 2026-01-01T00:00:00Z
And a parameter node exists for classification code "LIAB_INAS"
And feature "F" has never been configured at classification code "LIAB_INAS"
When the applicable value of feature "F" is resolved for classification code "LIAB_INAS" at resolution datetime 2026-04-01T00:00:00Z
Then the applicable value of feature "F" is "V"
```

### Scenario: An explicit absence marker is superseded by a concrete value

```gherkin
Given a parameter node exists for classification code "LIAB" with feature "F" set to value "V" at effective datetime 2026-01-01T00:00:00Z
And feature "F" is set to explicitly absent at classification code "LIAB_INAS" with an effective datetime of 2026-01-01T00:00:00Z
When Cameron submits account feature configuration for classification code "LIAB_INAS" with feature "F" set to value "W" and effective datetime 2026-01-01T00:00:00Z
Then the submission is accepted
And the applicable value of feature "F" for classification code "LIAB_INAS" at resolution datetime 2026-04-01T00:00:00Z is "W"
```

**Out of Scope:**
- Explicit absence at the account node level — that is a distinct mechanism not covered by this story.
- The behaviour of explicit absence through multiple levels of hierarchy — for example, whether explicit absence at `SAVE_INAS` also suppresses inheritance at `SAVE_INAS_2026`. That is a resolution semantics question for a dedicated story.
- Superseding a concrete value with an explicit absence marker — that is, reinstating inheritance suppression after it has been lifted. This story establishes that supersession of explicit absence is possible; the reverse direction is a separate story.

**Open Questions:**
None.