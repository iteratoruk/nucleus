# Persona: Ripley from Risk

## Role

Ripley represents the Compliance and Risk function: the internal stakeholder responsible
for ensuring that Nucleus supports the bank's regulatory obligations and risk management
requirements, principally through the correct application of restrictions, flags, and
account-level controls that prevent or constrain activity on accounts where regulatory,
fraud, financial crime, or credit risk conditions require it.

## Type

Internal authority — restriction and flag director, regulatory obligation holder.

## Responsibilities

Ripley does not operate accounts, configure products, or process payments. Ripley identifies
circumstances in which the normal operation of an account must be constrained, and directs
that constraints be applied. The execution of that direction — the actual application of a
restriction or flag in Nucleus — is Eddie's responsibility. Ripley is the authority that
determines whether a restriction is warranted and on what basis; Eddie is the operator who
enacts it.

Ripley's domain spans several distinct regulatory and risk regimes that share a common
operational expression in Nucleus — the restriction or flag on an account — but which differ
significantly in their legal basis, their consequences for the customer, and the obligations
they impose on the bank:

**Financial crime controls.** Restrictions applied under suspicion of money laundering,
fraud, or sanctions exposure. These may be imposed under legal obligation (e.g. a
production order, a restraint order, or a suspicious activity report freeze) and may carry
disclosure restrictions — Nucleus must not emit events that would tip off the subject.
The legal basis for these restrictions is external to the bank and not negotiable.

**Credit risk controls.** Restrictions applied because an account has entered a condition
that indicates credit deterioration: arrears, breach of a facility limit, or a change in
the customer's assessed risk profile. These are Ripley's concern in the sense that Ripley
defines the policy; Liam and Eddie are more directly involved in the operational response.

**Regulatory directions.** Restrictions applied because a regulator — the FCA, PRA, or
another authority — has directed the bank to constrain activity on an account or class of
accounts. These are non-negotiable and must be applied immediately and completely.

**Operational risk controls.** Flags applied to accounts that are in an unusual or
unresolved state: a dispute in progress, a data integrity concern, a pending investigation.
These are softer controls than regulatory restrictions but serve to alert operators and
prevent automated processing from continuing on an account where human review is warranted.

Ripley is also the owner of the policy framework that governs restriction precedence: when
multiple restrictions are in force on an account, which takes priority, and what actions
remain permissible under each combination. This framework must be encoded in Nucleus's
restriction domain as parameter-driven configuration, not as hardcoded logic.

## Goals

- Define and maintain the restriction and flag type taxonomy in Nucleus: the set of
  recognised restriction types, their operational effects on account behaviour, their
  precedence relationships, and the conditions under which they may be lifted.
- Direct Eddie to apply restrictions to specific accounts promptly when a triggering
  condition is identified, and receive confirmation that the restriction is in force
  before concluding the action.
- Direct Eddie to lift restrictions when the triggering condition is resolved, with
  confidence that Nucleus has correctly re-enabled the account behaviours that the
  restriction suspended.
- Receive notification when Nucleus autonomously detects a condition that Ripley's policy
  framework has defined as a restriction trigger — for example, when an account breaches
  an arrears threshold or when a scheduled operation fails in a way that requires
  human review.
- Ensure that restriction and flag events emitted by Nucleus carry sufficient detail for
  Robin to satisfy any regulatory reporting obligations associated with the restriction
  type, without Ripley needing to provide a separate data feed.
- Maintain an auditable record of every restriction applied and lifted: who directed it,
  on what basis, when it was applied, when it was lifted, and what activity was prevented
  or permitted during the restricted period.

## Constraints

- Some restriction types carry legal disclosure obligations that affect what Nucleus may
  emit on its event feed. A financial crime restriction applied under a suspicious activity
  report or a court order may be subject to tipping-off rules: Nucleus must not emit an
  event that identifies the account as subject to a financial crime investigation in a
  way that could reach the account holder or an unauthorised party. The event model for
  restriction types must accommodate structured confidentiality classifications on events,
  and the Kafka topic access controls must enforce them.
- Restriction types are not a fixed set. The regulatory environment changes; new risk
  policies are introduced; new restriction types will be required over time. The
  restriction taxonomy in Nucleus must be configurable — ideally through the parameter
  value hierarchy — rather than hardcoded. A new restriction type should not require a
  code change to introduce.
- Restriction precedence is a policy concern, not a technical one. When two restrictions
  are simultaneously in force on an account, the question of which takes precedence and
  what the combined effect is should be determined by Ripley's policy configuration, not
  by implementation order or arbitrary priority integers. The precedence model must be
  explicit, auditable, and testable.
- Certain restrictions must be applied atomically with the detection of the triggering
  condition. An arrears threshold breach that is detected but not immediately restricted
  creates a window in which prohibited activity could occur. Where Nucleus is the detector
  — through its own scheduled processing — it must apply the restriction within the same
  transaction as the detection, not as a subsequent step.
- Ripley operates under significant time pressure when a regulatory direction is received.
  A direction from the FCA to freeze an account does not come with a grace period. Nucleus
  must be capable of applying a restriction to an account in force immediately, with no
  dependency on batch processing or deferred execution.

## Integration Pattern

**Indirect — policy authority rather than direct integrator.**

Ripley does not have a direct technical integration with Nucleus in the way that Sasha,
Parker, or Alex do. Ripley's interaction with Nucleus is mediated through two channels:

**Via Eddie (synchronous, operator-initiated):** When Ripley directs that a specific
restriction be applied or lifted on a specific account, an operator acting under Ripley's
authority executes this through Eddie's Enterprise tooling. The attribution chain —
Ripley's direction, Eddie's execution, Nucleus's record — must be preserved in the audit
trail.

**Via parameter configuration (policy framework):** The restriction type taxonomy,
precedence rules, and automatic trigger conditions that Ripley owns are encoded in Nucleus
as configuration. The mechanism for this configuration — whether it uses the parameter
value hierarchy or a separate administrative API — is an open architectural question to
be resolved before restriction domain stories are implemented.

**Asynchronous (Kafka — inbound to Ripley):** Ripley subscribes to restriction and flag
lifecycle events from Nucleus — restriction applied, restriction lifted, automatic trigger
fired — for regulatory monitoring and audit purposes. Ripley also receives events for
conditions that Nucleus detects autonomously and which Ripley's policy framework has
designated as requiring human review.

## Interests By Domain Area

**Restrictions and flags:** Primary and defining stakeholder. The entire restriction and
flag domain in Nucleus exists to serve Ripley's regulatory and risk management obligations.
Restriction domain stories should be authored with Ripley (for policy and taxonomy stories)
or Casey (for stories about the customer impact of restrictions) as the primary persona,
with Eddie as the operational actor in both cases.

**Account servicing:** Moderate interest. Scheduled servicing operations — interest
application, maturity processing, payment collection — must respect account restrictions.
If a restriction prevents a scheduled operation from completing, Nucleus must record this
as a distinct event type rather than silently skipping the operation. Ripley needs to know
that a restriction had an operational effect; silent suppression of servicing is not
acceptable.

**Ledger entries:** Moderate interest. Restrictions may prevent ledger entries from being
posted. Where a restriction blocks a transaction that would otherwise have produced a
ledger entry, the blocking must be recorded in a way that Alex can reconcile against the
expected-but-absent entry. This is the complement of Alex's constraint about processing
gaps caused by restrictions.

**Payments:** High interest for financial crime restrictions. An outbound payment
attempted on a financially restricted account must be blocked by Nucleus before the
instruction reaches Parker. The blocking must be recorded, attributed to the restriction
type, and notified to the relevant parties in a manner consistent with any disclosure
obligations on the restriction type.

**Account open/close:** Moderate interest. A restriction may prevent account closure —
for example, a legally imposed freeze cannot be lifted simply because the customer
requests closure. Nucleus must enforce this and return a structured error that Eddie
can interpret without needing to understand the restriction's legal basis.

**Audit trail:** High interest across all domains. Ripley depends on Nucleus's audit
record being complete, immutable, and structured enough to support regulatory examination.
The `AuditService` and its event taxonomy must be designed with Ripley's examination
scenarios in mind, not just as a technical logging mechanism.

**Parameter value hierarchy:** Secondary interest. If restriction configuration is managed
through the parameter value hierarchy, Ripley has an indirect stake in the correctness
and accessibility of that configuration. Ripley needs to be able to verify that the
restriction taxonomy currently active in Nucleus reflects current policy, without
requiring an engineering investigation to do so.
