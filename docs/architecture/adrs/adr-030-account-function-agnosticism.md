# ADR-030: Account function agnosticism

**Date:** 2026-05-03
**Status:** Accepted

## Context

Many banking systems distinguish "internal accounts" — system-owned operational
accounts such as payment-operations-holding, profit-and-loss aggregation,
write-off, and fraud-holding — from "customer accounts" that are held against
an external party. The distinction is often made architecturally visible:
separate APIs, separate aggregates, separate persistence schemes, and sometimes
internal accounts treated as derived or calculated rather than fully
materialised facts.

Nucleus's Account context could adopt this distinction or refuse it. Adopting
it would create a structural divide that propagates across every operation,
every event, and every query: configurers and operators would have to
distinguish which kind of account they are addressing; the event vocabulary
would split; reporting and reconciliation would consume two streams.
Refusing it would treat every account uniformly and place the question of
"what role does this account perform in the system" outside the Account
context's domain model.

Several considerations push toward refusing the distinction. Reconciliation
(per Alex) requires uniform queryability of ledger entries against any
account, internal or customer; an internal account whose entries are
calculated rather than stored cannot be reconciled at the same level of
fidelity. Audit and regulatory obligations require that every account's
history be retrievable on demand, with no special handling for internal
categories. The principle that the Account aggregate's responsibility is "be
a target for ledger entries" fits internal and customer accounts identically.
And the practical observation that "internal" versus "customer" is a
contextual classification (whose stakeholder, what role) rather than a
structural one (the account itself is the same shape in either case) further
supports a single uniform aggregate.

External addressability — by sort code and account number (BBAN), or by IBAN
— is a related but distinct concern. Whether an account can receive an
inbound payment routed by a payments scheme is a function of the payments
infrastructure (which holds the BBAN-to-account-UUID mapping), not of the
account itself. Some accounts are addressable (a customer's payment-capable
product, a credit-recovery account that must receive third-party payments);
others deliberately are not (holding accounts, P&L, write-off, fraud).
Coupling addressability to the Account aggregate would entangle it with the
Payments scheme's evolution and would contaminate the aggregate with
concerns unrelated to its primary responsibility.

## Decision

The Account aggregate does not distinguish internal accounts from customer
accounts. Every account in Nucleus has the same structural shape: a UUID, a
stakeholder identifier, a ledger side, a status, an attachment to a
parameter node, and a resolvable accounting code. Every account supports the
same operations and exposes the same lifecycle. Every account's ledger
entries are uniformly queryable through the same query mechanism, with
materialisation strongly recommended for all categories — an internal
account's ledger entries are not a calculated shadow of some other account's
entries; they are entries against this account, posted as facts and stored
as such.

The function an account performs in the broader system — payment routing,
P&L aggregation, credit recovery, fraud holding, customer product — is
encoded by the account's stakeholder (which is the owning party, which may
be an external customer or an internal subsystem) and by the configuration
resolved against its node. It is not a property of the Account aggregate
itself. There is no `internalAccount` flag, no role enumeration, and no
discriminator at the Account level.

External addressability is a property of the Payments context, not of the
Account context. The Account aggregate has no `externallyAddressable`
attribute. When an account is required to be externally addressable, the
Payments context registers an address against the account's UUID and
maintains the mapping. When a payment arrives at an external address, the
Payments context resolves the mapping to the Nucleus UUID and posts the
corresponding ledger entries against that account. Some accounts in the
system will be externally addressable; others deliberately will not; the
Account context does not record the distinction and does not behave
differently in either case.

## Consequences

**Positive:** The Account context is simple and uniform. There is one kind
of account, one set of operations, one event vocabulary. New account
categories — a future operator team's holding account, a new internal
subsystem's infrastructure accounts, additional customer product types —
require no changes to the Account aggregate; they are accommodated by
registering a new stakeholder and defining the appropriate parameter
configuration. Reconciliation, audit, and reporting consume a uniform stream
of events and a uniform query surface. The Account aggregate is decoupled
from the Payments scheme's evolution.

**Negative:** Consumers that genuinely care about the internal/customer
distinction must derive it from contextual information — typically the
stakeholder identifier together with the classification code — rather than
from a first-class field. The distinction is not surfaced as a property of
the Account aggregate, so consumers that want to filter or partition by it
must be configured with the relevant stakeholder identifiers or
classification-code prefixes.

**Risks:** A consumer that misinterprets an internal account as a customer
account in a customer-facing context could expose internal operational state
inappropriately. Mitigation: customer-facing systems address customer
accounts through their configurer's account catalogue (Sasha, Liam, Maya),
which holds the mapping from customer-product accounts to its own customer
records; internal accounts are not in that mapping and therefore are not
exposed through customer-facing channels. A second risk is that
addressability decisions made by the Payments context get out of sync with
the configurer's expectations of which accounts should be addressable; this
is mitigated by the explicit registration model — addressability is a
deliberate Payments-context action, not a default.

## Alternatives Considered

A first-class distinction between internal and customer accounts — as
separate aggregates, or as a discriminator field on the Account aggregate —
was considered. It was rejected on the grounds described in Context: the
distinction would propagate across the surface area of the context for no
compensating benefit, and the Account aggregate would need to be revised
every time a new internal account category emerged.

Calculation of internal-account state rather than materialisation was
considered. It was rejected: ledger entries against internal accounts must
be retrievable on the same terms as those against customer accounts, for
reconciliation against the general ledger and for audit. Calculation
provides no audit trail for the calculated values themselves and breaks
reconciliation, since the GL holds posted aggregate positions that must be
matched against posted positions in Nucleus.

Treating addressability as an Account aggregate attribute was considered.
It was rejected: it would couple the Account aggregate to the evolution of
the Payments scheme (BBAN/IBAN/future scheme identifiers) and would
contaminate the aggregate with concerns unrelated to whether ledger entries
can be posted against it. The Payments context is the appropriate owner of
addressability because it owns the scheme integration.