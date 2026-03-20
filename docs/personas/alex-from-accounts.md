# Persona: Alex from Accounting

## Role

Alex represents the Reconciliation and Accounting value stream: a downstream consumer of
ledger entries and financial events from Nucleus, responsible for ensuring that the core
banking record is complete, internally consistent, and reconcilable against the bank's
general ledger and external settlement statements.

## Type

Passive observer — ledger event consumer and reconciliation querier.

## Responsibilities

Alex does not instruct Nucleus to open accounts, configure products, or initiate payments.
Alex observes the financial record that Nucleus maintains and is accountable for confirming
that it is correct: that every movement of money is accounted for, that debits and credits
balance, that the Nucleus ledger agrees with the general ledger, and that inbound and
outbound payment settlements reconcile against the positions Nucleus holds.

Where Robin's interest in ledger entries is analytical — what do these entries tell us about
product performance — Alex's interest is arithmetical and structural: are these entries
complete, correctly signed, correctly attributed to the right accounts, and reconcilable
against an external source of truth? Robin asks "how much interest did we earn this month?"
Alex asks "does the total of all interest ledger entries agree with what the general ledger
shows, to the penny?"

Alex operates under a hard constraint that Robin does not: a reconciliation break is not
a reporting anomaly to be investigated later. It is an immediate operational problem that
may indicate a processing failure, a data integrity issue, or a financial loss. Alex's
tolerance for ambiguity in the ledger record is effectively zero.

## Goals

- Receive a complete and correctly ordered stream of ledger entry events from Nucleus,
  carrying sufficient detail — debit/credit indicator, amount, currency, value date,
  effective date, entry type, account identifier, and classification code — to post to
  the general ledger without enrichment or inference.
- Reconcile the aggregate of Nucleus ledger entries against the bank's general ledger on
  a daily basis, with confidence that any discrepancy reflects a genuine exception rather
  than a gap in the event feed.
- Reconcile inbound and outbound payment settlements against Nucleus payment records,
  confirming that every settled payment corresponds to a Nucleus payment instruction and
  that every Nucleus payment instruction has a settlement outcome.
- Query Nucleus for the total ledger position across a defined set of accounts or
  classification codes as at a given date, to support period-end closing and audit.
- Receive notification of any correcting entries posted by Nucleus — reversals, adjustments,
  or backdated entries — with clear attribution to the original entry being corrected, so
  that the general ledger can be updated without manual investigation.

## Constraints

- Double-entry integrity is non-negotiable. Every financial transaction in Nucleus must
  produce ledger entries that balance: the sum of debits must equal the sum of credits
  within the transaction boundary. Alex must be able to verify this from the event record
  alone, without querying Nucleus for additional context. If Nucleus emits a ledger entry
  event that cannot be matched to its counterpart within the same transaction, Alex has
  an irreconcilable break.
- Value date and effective date are distinct and both are required. Value date is when the
  money movement is recognised for settlement purposes; effective date is when it is
  recognised for accounting purposes. These can differ — particularly for payments and
  for interest entries that are accrued in one period and applied in another. Alex requires
  both on every ledger entry event. A single "date" field is not sufficient.
- Correcting entries must be distinguishable from original entries. A reversal that
  carries the same entry type and structure as the original entry it reverses is
  irreconcilable without out-of-band knowledge. Nucleus must mark correcting entries
  explicitly and carry a reference to the entry being corrected.
- The event feed must be replayable. Alex must be able to re-consume the ledger entry
  event stream from a given point — for example, following a general ledger system
  outage — and arrive at the same reconciled position. This requires that events carry
  a stable, ordered identifier and that the feed is not destructively compacted.
- Reconciliation is time-sensitive. End-of-day reconciliation has a cut-off. If Nucleus
  has not emitted all ledger entry events for a processing day by that cut-off, Alex
  carries an open position that cannot be closed. Nucleus must have a defined and
  reliable processing day close behaviour.

## Integration Pattern

**Asynchronous (Kafka — inbound to Alex):** The primary integration. Alex consumes ledger
entry events, payment settlement events, and account lifecycle events from Nucleus. The
event feed must be ordered within a partition in a way that preserves the natural sequence
of entries within a transaction, and must be replayable from an arbitrary offset.

**Synchronous (REST — outbound from Alex):** Period-end and audit queries: total ledger
position for a set of accounts or classification codes as at a defined date; confirmation
that a specific transaction's entries are complete; retrieval of a specific correcting
entry and its original. Alex's query patterns are low-frequency and high-precision rather
than high-volume and analytical.

Alex is a **pure downstream observer** with zero upstream influence on Nucleus behaviour.
Alex initiates no state changes. However, where Alex identifies a reconciliation break,
the resolution may involve Eddie raising a correcting entry via the operational tooling
that Enterprise provides — Alex is the detector, Eddie is the remediation actor.

## Interests By Domain Area

**Ledger entries:** Primary stakeholder. The ledger entry event schema is the most critical
design concern for Alex. Every field matters: amount, currency, debit/credit indicator,
entry type, value date, effective date, account identifier, transaction identifier,
classification code, and — where applicable — the identifier of the entry being corrected.
The schema must be agreed with Alex (and Robin) before any ledger entry stories are
implemented.

**Payments:** Primary stakeholder for settlement reconciliation. Every payment that Nucleus
processes — inbound or outbound — must produce a settlement event that Alex can match
against the Faster Payments scheme settlement statement. Unmatched payments in either
direction are reconciliation breaks. Alex needs payment reference, scheme transaction
identifier, amount, currency, settlement date, and settlement status on every payment
event.

**Account open/close:** Moderate interest. Account opening and closing events affect the
population of accounts Alex must account for. Alex does not need the full lifecycle detail
that Robin requires, but does need to know when an account enters or leaves the population
so that period-end position queries return the correct set.

**Account servicing (interest accrual and application):** High interest in the ledger
dimension. Interest accrual produces ledger entries; interest application produces ledger
entries. Alex needs these to be correctly attributed and to balance within their respective
transaction boundaries. The distinction between an accrual entry (recognised in one period)
and an application entry (settled in a subsequent period) must be preserved in the event
type classification.

**Balances:** Moderate interest, primarily for period-end verification. Alex queries
aggregate positions rather than individual account balances. The point-in-time query
capability is important for audit and for resolving disputed reconciliation positions.

**Restrictions and flags:** Low direct interest. A restriction may prevent processing that
would otherwise produce ledger entries, creating a gap that Alex would detect as an
expected-but-absent entry. Alex needs to know restrictions exist as a possible explanation
for processing gaps, but does not need the operational detail that Eddie or the configurer
personas require.

**Parameter value hierarchy:** Indirect interest, identical to Robin's. Classification codes
must be present and consistent on all ledger entry events. They are Alex's primary key for
aggregating positions by product family for general ledger posting.
