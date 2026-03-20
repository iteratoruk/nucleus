# Persona: Robin from Reporting

## Role

Robin represents the Reporting value stream: a downstream consumer of lifecycle events and
account state from Nucleus, responsible for producing business intelligence, regulatory
submissions, and management reporting from the factual record that Nucleus maintains.

## Type

Passive observer — event consumer and state querier.

## Responsibilities

Robin does not instruct Nucleus to do anything. Robin observes what Nucleus reports and
transforms that information into structured outputs for business and regulatory consumers:
management dashboards, FCA returns, internal risk reports, product performance analytics,
and so on. Robin is accountable for the accuracy of those outputs, which means Robin is
dependent on Nucleus emitting complete, correctly sequenced, and correctly attributed events.

Robin operates across all value streams — Savings, Lending, and Mortgages — and must
therefore make sense of events and state from accounts with very different behavioural
profiles. Robin is not a domain expert in any one product type; Robin is a consumer of
the common language that Nucleus uses to describe all of them.

## Goals

- Receive a complete, ordered, and reliable stream of account lifecycle events from Nucleus
  so that the reporting record is consistent with the core banking record without requiring
  periodic reconciliation to detect gaps.
- Query account state from Nucleus at a point in time — not just current state — so that
  reports can be produced for any historical period without Robin needing to maintain its
  own historical snapshot store.
- Receive ledger entry events with sufficient attribution — account, classification code,
  event type, effective date, value date — to support both product-level and regulatory
  reporting without additional enrichment from upstream value streams.
- Receive account opening and closing events with the metadata necessary to classify and
  aggregate accounts by product family, value stream, and date cohort.
- Have confidence that events are not lost, duplicated, or delivered out of sequence in
  ways that would corrupt cumulative reporting figures.

## Constraints

- Robin is a consumer of facts, not a participant in transactions. Robin must never be in
  a position where a report depends on information that Nucleus has not yet emitted. If
  Nucleus processes an event asynchronously and there is a lag before the corresponding
  Kafka message is produced, Robin's reporting will lag by the same amount. This is
  acceptable provided the lag is bounded and consistent; silent gaps are not acceptable.
- Regulatory reporting has specific temporal requirements: certain returns must reflect the
  state of accounts as at a defined date (e.g. end of day, end of month). Robin relies on
  Nucleus's point-in-time balance and state query capability to satisfy these requirements.
  Robin cannot reconstruct point-in-time state by replaying events if Nucleus does not also
  expose a queryable snapshot.
- Robin is not a substitute for Reconciliation. Robin's interest is in business and
  regulatory reporting; the integrity of the ledger itself is Alex's concern. Where their
  interests overlap — particularly around ledger entry events — the event model must serve
  both without either persona requiring a separate feed.
- Robin consumes events from all three value streams. The event schema must be consistent
  enough that Robin can process events from Savings, Lending, and Mortgages accounts without
  stream-specific handling for common event types (account opened, account closed, ledger
  entry posted). Product-specific detail should be carried as structured metadata, not as
  variation in the core event structure.

## Integration Pattern

**Asynchronous (Kafka — inbound to Robin):** The primary integration. Robin consumes lifecycle
events published by Nucleus across all topics relevant to its reporting obligations. Robin is
a passive subscriber; it does not acknowledge or respond to events in ways that affect Nucleus
behaviour.

**Synchronous (REST — outbound from Robin):** Point-in-time state and balance queries,
used to produce regulated snapshots or to enrich event-driven reports with state that was
not carried in the event payload. Robin's query patterns tend toward bulk and scheduled
rather than interactive and real-time.

Robin is a **pure downstream observer**. It has no upstream influence on Nucleus behaviour
and initiates no state changes.

## Interests By Domain Area

**Account open/close:** High interest. Account opened and account closed events are the
foundation of Robin's cohort and stock reporting — how many accounts opened in a period,
by product, by value stream; what the attrition rate is; what the average account duration
is. These events must carry the classification code, value stream attribution, and opening
or closing date with precision.

**Ledger entries:** High interest. Ledger entry events are the raw material for interest
income reporting, fee income reporting, and product profitability analysis. Robin needs
the entry type, amount, currency, effective date, value date, and account classification
as a minimum on every event.

**Account servicing (interest application, maturity):** High interest. Interest application
events feed income accrual and recognition reporting. Maturity events are significant for
fixed-term product reporting and for cohort analysis. Robin needs these events to carry
sufficient detail to reconcile against ledger entries without requiring a separate query.

**Balances:** Moderate interest. Robin queries balances at defined reporting dates rather
than continuously. The point-in-time query capability — balance at T1 as observed from T2
— is more important to Robin than real-time balance accuracy.

**Payments:** Moderate interest. Payment events contribute to volume and value reporting
and, for lending accounts, to repayment performance analytics. Robin is interested in
payment outcome events (payment received, payment failed) rather than the mechanics of
payment initiation.

**Restrictions and flags:** Low interest in the operational sense, but relevant to
regulatory reporting. Certain restriction types may be reportable events (e.g. accounts
subject to a financial crime restriction may require disclosure). Robin needs restriction
events to carry a structured type code that can be used for classification without Robin
needing to understand the operational context of the restriction.

**Parameter value hierarchy:** Indirect interest. Robin does not consume the hierarchy
directly, but relies on classification codes being present and consistent on all events
and account records. The classification code is Robin's primary key for product-level
aggregation. If classification codes are inconsistent or absent, Robin's reporting
breaks silently.
