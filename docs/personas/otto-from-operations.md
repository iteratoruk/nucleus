# Persona: Otto from Operations

## Role

Otto represents the Platform Operations function: the internal stakeholder responsible for
the health, reliability, and operability of Nucleus as a running system. Otto is concerned
with what Nucleus does between requests — scheduled processing, infrastructure dependencies,
error conditions, dead-letter queues, and the signals that indicate whether the system is
functioning correctly and completely.

## Type

Internal operator — platform health monitor and operational exception handler.

## Responsibilities

Otto does not open accounts, configure products, or process payments on behalf of customers.
Otto ensures that the platform on which all of those things happen continues to function
correctly, and that when it does not, the failure is detected promptly, understood clearly,
and resolved without data loss or unrecoverable state.

Otto's concerns fall into three broad areas:

**Scheduled processing integrity.** Nucleus runs a significant body of autonomous scheduled
work: end-of-day accruals, interest application, maturity processing, payment settlement
windows. These jobs must complete within their processing windows, produce the correct
outputs, and leave no accounts in a partially processed state. Otto is the persona who
monitors this processing, detects when a job has not completed or has completed with errors,
and determines whether the failure requires automated retry, manual intervention, or
escalation to an engineering team.

**Infrastructure dependency health.** Nucleus depends on PostgreSQL, Redis, and Kafka. The
failure modes of each are distinct: a PostgreSQL outage during a scheduled job may leave
transactions in an indeterminate state; a Kafka broker unavailability may cause events to
queue or be lost; a Redis failure may affect caching behaviour or distributed locking in
ways that produce incorrect results under concurrent load. Otto monitors these dependencies
and owns the operational response when they degrade.

**Exception and dead-letter queue management.** Messages that Nucleus cannot process —
inbound Kafka messages that fail after exhausting retries, ISO 20022 messages from Parker
that cannot be parsed or routed, scheduled job executions that fail after retry — must land
somewhere observable and actionable. Otto is the owner of those queues and is responsible
for triaging, resolving, or escalating every item in them. An exception queue that grows
silently is an operational failure.

## Goals

- Observe the outcome of every scheduled processing run in Nucleus — which jobs ran, when,
  whether they completed successfully, how many accounts were processed, and whether any
  accounts were skipped or left in an error state — without requiring a database query or
  engineering investigation to obtain this information.
- Receive prompt, structured notification when a scheduled job fails, completes partially,
  or does not start within its expected window, with sufficient context to determine the
  appropriate response without needing to read application logs.
- Monitor the health of Nucleus's infrastructure dependencies continuously and receive
  notification of degradation before it produces visible processing failures.
- Inspect and act on every item in every dead-letter or exception queue: understand why
  the item failed, determine whether it is safe to replay, replay or discard it, and
  confirm that the replay produced the correct outcome.
- Verify that the processing day has closed correctly — that all end-of-day jobs have
  completed, all events have been emitted, and the system is in a clean state for the
  next processing day — before the cut-off that Alex depends on for reconciliation.
- Detect and respond to any condition in which Nucleus has processed a transaction
  partially: a ledger entry posted without its counterpart, a payment instruction
  dispatched without a corresponding internal record, a scheduled job that updated
  some accounts but not others due to a mid-run failure.

## Constraints

- Otto operates on-call. Operational failures do not respect business hours. Nucleus must
  be designed so that the information Otto needs to diagnose and respond to an incident is
  available through operational tooling — structured logs, metrics, health endpoints,
  dead-letter queues — without requiring access to production data or a running debugger.
  An incident that can only be diagnosed by reading raw database rows is an operability
  failure in the system design.
- Otto is not an engineer. Otto is an operations professional who understands the system's
  behaviour and failure modes but does not write code to resolve incidents. The operational
  surface of Nucleus — health endpoints, job status APIs, dead-letter queue management,
  replay tooling — must be designed for operator use, not for engineering convenience.
  This is a distinct requirement from Eddie's operational tooling, which is designed for
  customer-facing agents; Otto's tooling is concerned with system state, not account state.
- Partial processing is the most dangerous failure mode. A failure that halts processing
  completely is visible and recoverable: nothing has changed, nothing needs to be undone.
  A failure that processes some accounts but not others, or that posts one leg of a
  double-entry but not the other, creates inconsistent state that is difficult to detect
  and expensive to repair. Nucleus's scheduled processing architecture must be designed to
  fail atomically — either the entire run succeeds or it fails cleanly — and must produce
  observable evidence of which outcome occurred.
- Dead-letter queues must be finite and actionable. A dead-letter queue that accumulates
  items without bound, or that contains items that cannot be replayed without engineering
  intervention, is not operational tooling — it is a deferred problem. Every item that
  reaches a dead-letter queue must carry enough context for Otto to determine its origin,
  its failure reason, and whether replay is safe. Items that are unsafe to replay must be
  escalatable to engineering with the information needed to resolve them.
- Otto's monitoring must not depend on Nucleus being healthy to report that it is healthy.
  Health and readiness endpoints must be lightweight, independent of scheduled processing
  state, and available even when the application is degraded. Metrics and structured log
  output must be emitted in a way that an external monitoring system can consume without
  calling Nucleus itself.
- Processing window compliance is a shared constraint with Alex and Parker. Otto is
  responsible for ensuring that end-of-day processing completes before Alex's
  reconciliation cut-off, and that payment processing windows open and close correctly
  relative to Parker's scheme schedule. These are operational SLAs, not aspirational
  targets.

## Integration Pattern

**Operational surface (REST — outbound from Otto):**

- Structured health and readiness endpoints (`/actuator/health`, `/actuator/info` or
  equivalent) for monitoring system integration.
- Scheduled job status endpoints: current state, last run outcome, next scheduled
  execution, count of accounts processed and skipped in the last run.
- Dead-letter queue inspection and replay endpoints: list items, inspect item detail,
  replay item, discard item.
- Processing day status endpoint: whether the current processing day is open, in
  end-of-day processing, or closed, and which jobs have completed in the current day.

**Observability feed (structured logs and metrics — outbound from Nucleus):**

Nucleus emits structured JSON logs and Prometheus-compatible metrics that Otto's
monitoring infrastructure consumes. Key signals include: scheduled job lifecycle events
(started, completed, failed, skipped), infrastructure dependency health (database
connection pool, Kafka producer/consumer lag, Redis connectivity), transaction throughput
and error rates, and dead-letter queue depth.

**Asynchronous (Kafka — inbound to Otto, operational topics):**

Otto may subscribe to operational event topics — scheduled job outcomes, processing day
state transitions, dead-letter queue arrivals — for integration with alerting and
incident management tooling. These are distinct from the business event topics consumed
by Robin, Alex, and the configurer personas.

Otto does not initiate state changes in Nucleus's business domain. Otto's write operations
are limited to the operational surface: replaying dead-letter items, acknowledging
exceptions, and triggering manual job executions within defined safe parameters.

## Interests By Domain Area

**Scheduled processing (interest accrual, application, maturity, payment windows):**
Primary stakeholder for operational outcomes. Every scheduled job in Nucleus is Otto's
concern from the moment it is due to run. Otto needs to know: did it run, did it
complete, how many items did it process, did any items fail, and if so what were they.
The `ScheduledTaskStartedEvent` and `ScheduledTaskFinishedEvent` audit events emitted by
Quartz are the foundation of Otto's visibility into this domain, but they must carry
sufficient detail — item counts, error summaries, duration — to be operationally useful
rather than merely confirmatory.

**Kafka dead-letter queues:** Primary stakeholder. Every `@TransactionalRetryingKafkaListener`
in Nucleus will exhaust its retry policy on some messages. Those messages must reach a
dead-letter topic that Otto monitors. Otto needs the original message, the failure reason,
the retry history, and the topic and partition from which the message originated. Without
this, messages that cannot be processed disappear silently.

**Processing day close:** Primary stakeholder, jointly with Alex. Otto owns the operational
responsibility for ensuring processing day close completes correctly and on time. Alex owns
the downstream reconciliation dependency. The processing day close event — whatever form
it takes — must be observable by both.

**Infrastructure health:** Primary stakeholder. PostgreSQL, Redis, and Kafka are invisible
to every other persona when they are healthy. When they degrade, Otto is the first line of
response and must have the monitoring signals to detect degradation before it produces
visible business failures.

**Ledger entries and payments:** Indirect interest. Otto does not validate the business
correctness of ledger entries or payments — that is Alex's domain. Otto's interest is in
whether the processing that produces them completed correctly: were all accounts processed,
were all messages dispatched, did any transactions roll back and leave partial state.

**Restrictions and flags:** Indirect interest. A restriction applied atomically during
scheduled processing — Ripley's atomicity constraint — is relevant to Otto because it means
a scheduled job may complete with some accounts restricted as a side effect. Otto needs job
completion reports to distinguish between accounts processed successfully, accounts processed
with restrictions applied, and accounts that failed to process entirely.

**Audit trail:** Moderate interest. The audit trail that Ripley and Eddie depend on for
compliance is also Otto's primary diagnostic tool for incident investigation. Otto needs
the audit trail to be queryable by time window and event type, and to be available even
when the event that triggered an incident was itself the last successfully recorded entry.
