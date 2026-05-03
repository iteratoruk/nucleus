# ADR-026: Accounting code immutability under non-CLOSED accounts

**Date:** 2026-05-03
**Status:** Accepted

## Context

The accounting code value resolved for a given account determines the structure
of the ledger positions accumulated against that account. When ledger entries
are posted, positions update at every prefix of the resolved accounting code. A
position is a posted financial fact, attributed to the accounting code in force
at the time of accumulation; it is not retroactively migrated when the resolved
code subsequently changes.

A change to the accounting code parameter value at a node, where active
accounts are attached at that node or any descendant, would therefore orphan
the accumulated positions: existing positions remain under the previous code
while new positions accrue under the new code, and reconciliation against the
general ledger (a continuous obligation per the Alex persona) becomes unable to
relate the two without out-of-band knowledge. Migrating positions between codes
is not a routine Nucleus operation; it requires coordinated freeze of affected
accounts and out-of-band remediation with the installation owners.

The openness categories defined in the Parameter Value Hierarchy domain model
(`GLOBAL`, named processing boundary, `PROSPECTIVE_ONLY`, per ADR-017 and
ADR-018) govern the validity of the *effective datetime* of a parameter write.
None of them governs whether the write itself is permitted, regardless of
effective datetime. A future-effective-dated change to the accounting code
would still take effect when its effective datetime arrived, orphaning
positions that have accumulated under the prior value; a backdated change
within an open window does the same thing in the opposite temporal direction.
The constraint that prevents the change is therefore structural and not
date-based.

## Decision

A write to an existing accounting code parameter value at a node is rejected
if any non-`CLOSED` account is attached at the node or at any descendant
node. Non-`CLOSED` here covers both `OPEN` and `PENDING_CLOSURE` accounts:
`OPEN` accounts continue to accumulate positions, and `PENDING_CLOSURE`
accounts may still accumulate final positions before closure completion. Both
would be harmed equally by an accounting code change.

The first write of an accounting code value at a node — where no prior value
exists at that node — is always permitted. No supersession occurs, and no
position has yet been accumulated under any value at this node, so no
orphaning is at issue. Subsequent writes (supersessions) are subject to the
constraint above.

A write that the constraint forbids is rejected at the account-features API
boundary with a structured error identifying the affected node, the
parameter key (`accounting.code` per the ADR-025 candidate's illustrative
naming), and the categories of attached accounts that prevent the change. The
error is sufficiently specific that the configurer can identify which
accounts must be closed (or migrated out-of-band) before the change can
proceed.

`CLOSED` accounts in the subtree do not constrain the write. Their positions
are historical and attributable to the accounting code that was in force at
the time of accumulation; future writes at the node do not affect their
attribution. A subtree from which all accounts have been closed permits
supersession freely, since no active account would be orphaned.

The constraint applies regardless of the proposed effective datetime. A
future-effective-dated change is rejected on the same grounds as a
present-effective change: when the effective datetime arrives, accumulation
under the new code begins for all then-active accounts in the subtree,
orphaning the prior positions. Backdating to a closed period, where
permissible at all under the openness model, is also rejected on these
grounds.

The mechanism for changing an accounting code in the presence of active
accounts is out-of-band migration: a coordinated procedure with the Nucleus
installation owners that freezes affected accounts, reattributes their
positions to the new code, and unfreezes them. The API boundary does not
expose this path; it is a manual operational procedure performed in
conjunction with the affected configurer's value stream.

## Consequences

**Positive:** The constraint preserves reconcilability of ledger positions
with the general ledger across the lifetime of every account. Accidental
change to an accounting code that would orphan positions is structurally
impossible through normal API access. The reconciliation invariants Alex
depends on are protected at the point of attempted change rather than
discovered as a break afterwards.

**Negative:** Configurers who realise after the fact that an accounting
hierarchy is misconfigured cannot correct it through normal API access;
out-of-band migration is required. This raises the cost of mistakes in
initial accounting hierarchy design and creates an asymmetry between the
ease of setup (a single write) and the difficulty of correction (a
coordinated freeze-and-migrate). The asymmetry is appropriate given the
financial integrity at stake but should be surfaced clearly in configurer
guidance.

**Risks:** Configurers may be tempted to work around the constraint by
closing all accounts in a subtree, changing the code, and opening
replacements — effectively a manual migration that loses the audit trail of
the original positions. Mitigation: the documented out-of-band migration
procedure should be the only sanctioned path, and large-scale closures
attributed to a configuration change should be detectable as an operational
red flag through normal monitoring of the account lifecycle event stream.

## Alternatives Considered

Treating the accounting code's mutability under the standard openness model
was considered. It was rejected: openness categories govern effective
datetime validity, not whether a write itself is permissible. A
`PROSPECTIVE_ONLY` classification would not help — a prospective change
still arrives, and when it arrives it orphans positions. The harm is
independent of effective datetime.

Permitting supersession with retroactive position migration handled
automatically by Nucleus was considered. It was rejected: position migration
is not a domain operation Nucleus can perform safely as a side effect of a
parameter write. It requires coordinated freezing of affected accounts to
prevent in-flight ledger entries during migration, reconciliation against
external systems whose positions reflect the old code, and orchestration
with the affected configurer's value stream — none of which fits the
synchronous semantics of the account-features API.

Treating the constraint as an advisory warning (the write succeeds with a
notification of orphaned positions) was considered. It was rejected: silent
orphaning of positions is exactly the harm the constraint exists to
prevent. Demoting it to a warning would not change the operational reality
that reconciliation breaks and that recovery requires out-of-band action; it
would only make the break harder to predict.