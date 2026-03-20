# ADR-002: Closed Period Governance

**Date:** 2026-03-20
**Status:** Accepted

## Context

The parameter value hierarchy domain model establishes that parameter values with
effective dates in closed processing periods may not be set or superseded. This protects
the integrity of the historical record: once all scheduled financial processing for a
business date has completed and its results are final, the configuration that governed
that processing must not be altered retroactively, because the ledger entries produced
under it are immutable facts and no mechanism exists to reconcile them against a revised
configuration without full reprocessing.

The domain model also establishes that a parameter value with an effective date in the
past but within an open period is permitted — this is late registration, not backdating,
and carries no consistency risk because the financial processing for that period has not
yet been finalised.

The distinction between these two cases depends entirely on what it means for a period
to be closed. Three questions must be answered:

1. What event or condition constitutes period close for a given business date?
2. Which context owns and signals that close?
3. How does Parameter Configuration enforce the closed-period boundary at write time,
   and what happens when a period must be reopened?

## Decision

**Period close is tied to processing completion, not elapsed calendar time.** A business
date is closed when all scheduled financial processing for that date has completed
successfully and its results are considered final. A business date is not closed by the
passage of midnight or by any other calendar event. This distinction matters: a system
failure during end-of-day processing may leave a business date unprocessed after it has
passed on the calendar. That date remains open — and late parameter registration for it
remains permitted — until processing completes.

**The Account Servicing context signals period close.** The Account Servicing context is
the context that executes scheduled processing (interest accrual, payment processing, and
analogous end-of-day jobs) and is therefore the authoritative source of the fact that
processing for a given business date is complete. When end-of-day processing for business
date D finishes successfully, the Account Servicing context emits a `PeriodClosed` event
carrying D as the closed business date. Parameter Configuration consumes this event and
records D as closed.

The counterpart event is `PeriodReopened`. If a closed period must be reopened —
following a processing failure, a reprocessing instruction, or an operational recovery —
the Account Servicing context emits `PeriodReopened` carrying the business date to be
reopened. Parameter Configuration consumes this event and removes D from its set of
closed periods. Parameter value submissions with effective date D are then permitted again
until a subsequent `PeriodClosed` event for D is received.

**Parameter Configuration enforces the boundary at write time.** When the account-features
API receives a PUT or PATCH request, it checks whether any effective date in the
submission falls on a closed business date. If any does, the entire request is rejected
with a structured error identifying which effective dates are in closed periods. The
rejection is total — consistent with the all-or-nothing validation rule that applies to
all account-features submissions. No partial application is permitted.

Parameter Configuration maintains a set of closed business dates, updated by consuming
`PeriodClosed` and `PeriodReopened` events. The check at write time is: for each
effective date in the submission, is that date in the closed set? If yes, reject.

## Consequences

**Positive:**

- The closed-period boundary is an explicit, observable state: a business date is either
  in the closed set or it is not. Configurers receiving a rejection can be told precisely
  which effective date triggered it.
- Late registration is correctly permitted: a configurer who was unable to submit a
  parameter value on the intended date, for any reason, may do so at any point until
  the relevant business date is closed by processing completion. They are not penalised
  by calendar time alone.
- The enforcement boundary is aligned with the integrity boundary. The ledger entries
  for a business date are not final until processing for that date is complete. The
  parameter record for that date is protected from that same moment — no earlier, no
  later.
- Period reopening is explicitly modelled. Operational recovery scenarios that require
  reprocessing a business date do not require workarounds or manual overrides of the
  parameter store; they are handled through the same event mechanism as the original
  close.

**Negative:**

- Parameter Configuration acquires a runtime dependency on events from the Account
  Servicing context. If `PeriodClosed` events are delayed, lost, or arrive out of order,
  Parameter Configuration's view of closed periods may diverge from the Account Servicing
  context's actual processing state. The boundary enforcement is only as reliable as the
  event delivery mechanism.
- The set of closed business dates must be stored durably in Parameter Configuration.
  It cannot be reconstructed from the account-features write history alone; it must be
  sourced from the event log of `PeriodClosed` and `PeriodReopened` events.
- Operational tooling for the Account Servicing context must support explicit
  `PeriodReopened` emission for recovery scenarios, not just reprocessing. If the
  tooling only supports reprocessing without signalling reopening, Parameter Configuration
  will continue to reject submissions for the reopened period during reprocessing.

**Risks:**

- **Duplicate or replayed `PeriodClosed` events** for an already-closed date must be
  idempotent. Parameter Configuration must not reject a second `PeriodClosed` for a date
  that is already in the closed set. Similarly for `PeriodReopened`.
- **A `PeriodReopened` event followed immediately by configuration changes** creates a
  window in which parameter values for the reopened period can be modified while
  reprocessing is in flight. If reprocessing resolves parameter values concurrently with
  incoming writes, the resolution results may be inconsistent within the same reprocessing
  run. The Account Servicing context should not begin reprocessing a period until it has
  confirmed that no configuration writes are in flight for that period. The sequencing of
  `PeriodReopened`, configuration stabilisation, and reprocessing start is an operational
  concern that must be addressed in the reprocessing workflow, not in Parameter
  Configuration itself.
- **Clock skew between business date and effective date granularity.** If effective dates
  are recorded at day granularity and `PeriodClosed` carries a business date at day
  granularity, the boundary is unambiguous. If either is recorded with time-of-day
  precision, the comparison requires a defined convention (e.g., "effective date
  2026-03-19 means 2026-03-19T00:00:00Z"). This must be consistent across all contexts
  that produce or consume effective dates.

## Alternatives Considered

**Calendar cut-off (midnight closes a period).** A business date D is closed at midnight
at the end of D, by definition. No event is required. This is the simplest model to
implement and requires no inter-context communication. It is rejected because it decouples
the closed-period boundary from processing completion. A system failure during end-of-day
processing on D would leave D unprocessed, but the calendar model would still mark D as
closed at midnight, preventing late parameter registration that might be needed before
reprocessing. The boundary must reflect operational reality, not calendar time.

**Operational instruction (period close by explicit command).** An operator explicitly
closes each business date via operational tooling after confirming that processing has
completed. This provides maximum control and audit visibility. It is rejected as the
primary mechanism because it introduces a mandatory manual step in a process that should
be automatable and is already signalled by processing completion. A missed or delayed
close instruction would leave periods open indefinitely. Operational override capability
(the ability for an operator to force-close or force-reopen a period outside the normal
event flow) may be provided as a secondary mechanism for exceptional circumstances, but
must not be the primary one.

**Parameter Configuration infers closure from resolution call patterns.** Parameter
Configuration observes which business dates have been used as resolution dates in
processing calls and infers that a date is closed once it has been resolved against and
a defined interval has elapsed. This is rejected because closure becomes implicit and
emergent rather than explicit and observable. The closed-period set cannot be inspected
or reasoned about from first principles; it depends on the pattern of resolution calls,
which may vary with processing failures, retries, and operational anomalies.