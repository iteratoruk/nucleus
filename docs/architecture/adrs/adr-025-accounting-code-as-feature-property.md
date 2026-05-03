# ADR-025: Accounting code as a feature property

**Date:** 2026-05-03
**Status:** Accepted

## Context

Every account in Nucleus must have an accounting code: a hierarchical identifier
under which the Ledger context aggregates the account's positions. Given an
accounting code such as `LIAB_RETL_SAVE`, Nucleus maintains positions at every
prefix (`LIAB`, `LIAB_RETL`, `LIAB_RETL_SAVE`); when a ledger entry is posted to
an account, positions update at every level of the account's accounting
hierarchy. This is the structure against which Alex reconciles Nucleus's record
to the bank's general ledger.

The accounting code shares the syntactic format of a classification code
(dot-free, underscore-delimited, 4-character uppercase alphanumeric segments)
but is a distinct concept. A given account's classification code (its product
identity) and its accounting code (its ledger position structure) may coincide,
share a prefix, or diverge entirely. Three hierarchies share this syntactic
format and must not be conflated: the classification (product) hierarchy
governing feature resolution, the parameter value hierarchy realising it as a
resolvable tree of nodes and values, and the accounting hierarchy governing
ledger position aggregation.

The accounting code value for any given account must be resolvable at opening
(an account whose accounting code does not resolve cannot be opened), must
remain stable for active accounts (changes orphan accumulated positions; the
ADR-026 candidate addresses this), and must be consistent with the account's
ledger side (positions exist on one side or the other; an asset account cannot
have liability-side positions and vice versa).

The Account Feature Catalogue and the Parameter Value Hierarchy already provide
the machinery for hierarchical, resolvable, validated values: feature-namespaced
parameter keys (per ADR-008), ledger-side applicability enforcement at the
catalogue boundary (per ADR-005 and ADR-011), the standard resolution walk.
The accounting code's behaviour fits that machinery in most respects.

## Decision

The accounting code is modelled as a property of an account feature in the
Account Feature Catalogue. The illustrative naming is feature `accounting`,
property `code`, parameter key `accounting.code`; the exact catalogue
identifiers are finalised when the feature is added to the Account Feature
Catalogue domain model document. This ADR commits to the principle, not to the
specific names.

The feature is applicable to both ledger sides (`ASST` and `LIAB`). The
resolved value's first segment must equal the ledger side of the node at which
it is written. This is enforced at the account-features API as a write-time
validation per the catalogue's existing ledger-side enforcement model: a write
of an accounting code value whose first segment does not match the writing
node's ledger side is rejected with a structured error.

The property's openness category is `GLOBAL`: from an effective-datetime
perspective, any effective datetime is permitted. The structural constraint
that prevents supersession in the presence of non-`CLOSED` accounts (ADR-026
candidate) is orthogonal to openness and applies independently. The
combination — `GLOBAL` openness plus the structural constraint — produces the
practical behaviour that an accounting code can be set freely until accounts
exist that depend on it, after which it becomes effectively immutable until
out-of-band migration intervenes.

At account opening, the accounting code is resolved against the account's
classification node and ledger side. Resolution must yield a value: if no
value or explicit absence resolves at any level of the hierarchy, the opening
is rejected with a structured error. The resolved value's first segment must
equal the account's ledger side; a mismatch (which would indicate a corrupted
write somewhere in the resolution lineage) rejects the opening. The resolved
accounting code is recorded on the account at opening and on the
`AccountOpened` event, where it is consumed by the Ledger context to establish
the account's initial position structure.

When an account is transferred between nodes (per the Account Node Attachment
aggregate), its resolved accounting code may change because the new node's
position in the hierarchy resolves a different value. This is acceptable and
is the configurer's responsibility to anticipate; the new accounting code is
carried on the `AccountTransferred` event, where the Ledger context consumes
it to update the account's position structure for entries posted from that
moment forward. Positions accumulated under the prior accounting code remain
attributed to it.

## Consequences

**Positive:** Reuse of the catalogue and parameter value hierarchy
infrastructure is direct: the accounting code resolves through the same
mechanism as any other feature property, with no need for a separate storage
or resolution path. Ledger-side consistency is enforced at the catalogue
boundary, the same point as for every other feature. The hierarchical
inheritance behaviour falls out for free: a configurer that sets an
accounting code at `LIAB` inherits it across all liability accounts unless
overridden at a more specific node, which matches the typical structure of a
bank's chart of accounts. The relationship between accounting code and
account lifecycle is expressed at the catalogue level alongside other feature
properties.

**Negative:** The structural-immutability constraint (ADR-026 candidate) is a
special case that does not fit the openness category model and requires a
distinct enforcement path in the account-features API. The accounting code
becomes one of several feature properties whose treatment varies in detail
from the canonical case (others being `BUSINESS_DAY_CLOSE`-bound and
`PROSPECTIVE_ONLY` properties), but the variation is principled and is
documented at the property definition.

**Risks:** A misconfigured accounting code at a high-level node could affect
the resolved value for many accounts opened against descendant nodes. This is
mitigated by ledger-side validation rejecting obviously wrong values at write
time and by the active-account constraint preventing in-place change once
accounts depend on the value. A configurer who realises after the fact that
an accounting hierarchy is misconfigured must use out-of-band migration to
correct it.

## Alternatives Considered

A first-class accounting code concept stored independently of the parameter
value hierarchy was considered. It was rejected: it would duplicate the
resolution mechanism, fragment the configuration story (configurers would
write feature configuration in one place and accounting codes in another),
and offer no compensating benefit.

A separate "accounting hierarchy" tree, parallel to the parameter value
hierarchy, was considered as the home for accounting code values. It was
rejected for similar reasons: the resolution logic would have to be
duplicated, and the configurer experience would suffer.

The accounting code as a non-feature attribute of the Account aggregate (set
at opening, with its own update API) was considered. It was rejected: it
would lose the hierarchical resolution behaviour that is the principal reason
for placing the value in the parameter hierarchy in the first place. A
configurer would have to set the accounting code on every account
individually, rather than once at an appropriate node and inherit from there.