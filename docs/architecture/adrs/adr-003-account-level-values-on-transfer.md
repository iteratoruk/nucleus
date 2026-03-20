# ADR-003: Account-Level Parameter Values on Node Transfer

**Date:** 2026-03-20
**Status:** Accepted

## Context

An account's parameter values sit at the account node — the most specific level of the
resolution hierarchy. They are set at account opening time or subsequently via the account
features API, and they take precedence over all classification-code-level values during
resolution. They represent deliberate decisions made about a specific account: overrides
of inherited configuration, exceptions to product-family defaults, or account-specific
parameters agreed at origination.

When an account is transferred from one parameter node to another, the node attachment
changes. The account now inherits from a different branch of the classification tree. The
classification-code-level configuration that forms the context for the account's
account-level values may therefore change materially: features present at the origin node
may not be present at the destination node, values that made sense as overrides at the
origin may not make sense as overrides at the destination, and the inherited baseline
against which the account-level values are applied may be entirely different.

The question this ADR resolves is what happens to account-level parameter values when the
account is transferred: whether they are preserved unchanged, cleared entirely, or
revalidated against the destination node's configuration with valid values preserved and
invalid values cleared.

## Decision

Account-level parameter values are preserved unchanged on node transfer. The transfer
operation changes the account's node attachment record and nothing else. Account-level
values are not examined, modified, or cleared as part of the transfer.

The account node is the most specific level of the resolution hierarchy, and its contents
are a property of the account, not of the node the account is attached to. A transfer
changes where the account sits in the classification tree; it does not change the account
itself. Account-level values represent decisions that were made about this specific account
— decisions that may have involved negotiation, exception approval, or product-specific
agreement — and those decisions are not implicitly revoked by a reclassification. If a
configurer wishes to clear or change account-level values following a transfer, they may
do so explicitly via the account features API after the transfer is complete.

## Consequences

**Positive:**

- A node transfer has a single, well-defined effect: the account's classification node
  changes. It has no secondary effects on account-level state. The operation is
  predictable and its scope is bounded.
- Account-level values set by a configurer are never modified by an operational action
  that the configurer did not explicitly direct at those values. There is no silent data
  modification. A configurer auditing account-level values after a transfer will find them
  exactly as they were before.
- The transfer operation is simple to implement and simple to reason about. It does not
  need to read, evaluate, or modify account-level parameter values; it creates a new node
  attachment record and seals the previous one.
- If post-transfer adjustment of account-level values is needed, the configurer performs
  it explicitly using the same API they used to set those values originally. The action is
  visible, auditable, and deliberate.

**Negative:**

- An account-level value that was semantically appropriate as an override at the origin
  node may be semantically inappropriate at the destination node — either because the
  relevant feature does not exist at the destination node, because the inherited value it
  was overriding no longer applies, or because the override's magnitude is no longer
  sensible relative to the new inherited baseline. Nucleus does not surface this misalignment
  automatically; the configurer is responsible for reviewing account-level values after
  a transfer.
- The resolution walk for a transferred account may produce results that do not match
  what a configurer would expect by inspecting the destination node's configuration alone,
  because account-level values continue to take precedence. A configurer who is unaware of
  pre-transfer account-level values may find the resolved configuration surprising.

**Risks:**

- **Stale account-level values masking destination node configuration.** An account-level
  value that overrides a key will continue to take precedence in the resolution walk at
  the destination node, regardless of what the destination node's configuration specifies
  for that key. If the account-level value was set to override a specific inherited value
  at the origin node, and the destination node carries a different inherited value for the
  same key, the account-level override will mask the destination node's configuration
  without the configurer necessarily being aware. The resolved configuration is
  deterministic and correct by the rules of the hierarchy; it may not be what the
  configurer intended after the transfer.

  The `AccountTransferred` event signals the configurer that a transfer has occurred and
  provides the prompt — and the responsibility — to review account-level values for
  continued appropriateness. The optional hypothetical query endpoint (OQ-4) would
  support this review by allowing the configurer to inspect what would resolve for the
  account under the destination node's configuration alone, making any divergence between
  the inherited configuration and the account-level overrides visible before deciding
  whether to adjust.

- **Long-lived accounts with accumulated account-level values.** An account that has been
  active for many years may have accumulated multiple account-level values set at
  different points in its history, some of which may no longer reflect current intent.
  A node transfer is an inflection point that could prompt a review of accumulated
  account-level state, but under this decision it does not force one. Operational
  processes that govern node transfers should include a step for the configurer to review
  account-level values as part of the transfer workflow, not as an afterthought.

## Alternatives Considered

**Clear all account-level values on transfer.** The transfer operation clears the account
node entirely: all account-level parameter values are removed, and the account inherits
freshly from the destination node. This produces a clean state and eliminates the risk of
stale account-level values masking destination node configuration. It is rejected because
it silently destroys configurer decisions without explicit instruction. An account-level
value may represent a significant product exception: a bespoke interest rate agreed with
a specific customer, a waived fee, an individually negotiated term. Clearing these values
without the configurer explicitly requesting the clear is data loss, and there is no
recovery path — the values are gone and must be reconstructed from out-of-band records.
The configurer who transferred the account to reclassify it did not instruct Nucleus to
remove any configuration; that secondary effect is neither requested nor expected. Rejected.

**Revalidate account-level values against the destination node's feature configuration,
preserving valid values and clearing those no longer applicable.** The transfer operation
inspects each account-level value, checks whether the corresponding feature exists and is
applicable at the destination node, preserves values for applicable features, and clears
values for inapplicable features. This appears to be a middle path that avoids both
stale-value risk and wholesale data loss. It is rejected for three reasons. First, it
still performs a silent clearing action — values for features deemed inapplicable are
removed without the configurer explicitly requesting the removal, which is the same
objection as to full clearing, applied selectively. Second, "applicable at the destination
node" is not straightforwardly defined: a feature absent from the destination node's own
configuration may still be applicable if it exists at an ancestor node, and clearing an
account-level value for such a feature changes what the resolution walk returns without
the feature being genuinely absent from the hierarchy. Third, the partial nature of the
clearing makes the outcome difficult to predict or inspect: the configurer cannot know in
advance which values will survive the transfer without knowing the full feature
configuration of the destination node and every ancestor. Predictability favours preserve
over selective clear. Rejected.