# ADR-004: Business Date as Resolution Reference in Scheduled Processing

**Date:** 2026-03-20
**Status:** Accepted

## Context

The resolution function returns the parameter value whose effective date is the latest
date on or before the resolution date. The resolution date is therefore the critical
variable that determines which configuration governs any given processing operation.

In an API request context — a configurer querying the effective configuration for a node,
or an account being opened — the resolution date defaults to the current date when not
explicitly supplied. This is correct: a configurer querying current configuration wants
the configuration that is applicable now.

Scheduled processing contexts are different in a way that matters. End-of-day jobs
(interest accrual, payment processing, maturity handling, rate transitions) execute
against a specific business date — the date for which the financial events being processed
are recognised. A job processing business date 2026-04-01 must apply the configuration
that is applicable on 2026-04-01, regardless of when the job actually runs.

The problem arises because scheduled jobs may not run at the moment their business date
implies. A job for 2026-04-01 is typically initiated late on 2026-04-01 or in the early
hours of 2026-04-02, but failures, retries, and reprocessing may cause it to execute
hours or days after the business date has passed. If the resolution function reads the
system clock at execution time, it will find parameter values that became effective after
2026-04-01 — including values that were not applicable on the business date being
processed. A rate change effective 2026-04-02 would be applied to 2026-04-01 accruals.
The inverse also holds: a job for 2026-04-01 running early (before midnight, during
pre-processing) would resolve against a time still within 2026-04-01, which appears
correct but only by coincidence.

The Maya rate transition use case makes the stakes concrete. A mortgage rate transitions
on a defined business date, registered in advance as a future-dated parameter value.
The new rate must be applied to accruals for that business date and not before. A
late-running job that defaults to wall-clock time would correctly apply the new rate
(the date has passed, so the value is effective); but a reprocessing job triggered after
the next rate change has been registered would find an even newer value, producing
accruals for 2026-04-01 under a rate that did not become effective until later. The
system clock cannot be the arbiter of which configuration governed a past business date.

## Decision

In all scheduled processing contexts, the business date being processed is the explicit
resolution date supplied to the resolution function. The resolution function does not
read the system clock. The business date is a parameter of the processing job, passed
explicitly from the job entry point through to every resolution call within that job's
execution.

There is no implicit default to "now" in scheduled processing. A resolution call made
within a scheduled job that does not supply an explicit resolution date is a programming
error, not a convention.

In API request contexts, the resolution date defaults to the current date when not
supplied by the caller. This default applies only at the API boundary, as an explicit
substitution at the point the request is received. It does not propagate into the
resolution function as a dependency on system time; the substituted date is passed as a
concrete value.

## Consequences

**Positive:**

- Scheduled processing is deterministic with respect to time. Rerunning a job for a
  given business date — whether as a retry, a reprocessing operation, or a replay —
  will resolve the same parameter values as the original run, regardless of when the
  rerun executes. The results are reproducible.
- Late-running jobs produce correct results. A job for business date D that runs at
  02:00 on D+1 resolves against D, not D+1. Parameter values effective on D+1 are not
  applied to D's processing.
- Future-dated values are protected. A parameter value with effective date D+1 cannot
  be accidentally applied to processing for D, regardless of execution timing.
- Reprocessing is safe. If a period is reopened and processing is rerun for a past
  business date, the resolution function will find the same configuration that governed
  the original processing run (subject to any parameter value changes made during the
  reopening window, which is a concern addressed in ADR-002).

**Negative:**

- The business date must be propagated explicitly through the call stack from the job
  entry point to every resolution call. It cannot be read from a shared context or
  inferred at the point of resolution. This imposes a discipline on scheduled processing
  code: the business date is an argument, not an ambient value.
- Testing scheduled processing components requires supplying a synthetic business date
  that may differ from the current wall-clock date. Test harnesses and test data must
  accommodate this.
- The distinction between the two contexts — "default to now" at API boundaries,
  "always explicit" in scheduled processing — must be understood and respected by all
  contributors. The failure mode is silent: a scheduled job that calls the resolution
  function without an explicit date will compile and run, but will produce incorrect
  results only under specific timing conditions that may not arise in normal testing.

**Risks:**

- **Silent misconfiguration in scheduled processing.** A resolution call that defaults
  to wall-clock time will produce correct results in most test scenarios, because tests
  are typically run at approximately the business date being tested. The defect surfaces
  only when a job runs significantly late, when reprocessing is performed, or when a
  parameter value transitions exactly at the boundary of a business date. These
  conditions are not routinely exercised in integration testing. Defensive test cases
  that supply a business date materially different from the current date — deliberately
  simulating late execution or reprocessing — must be included for any component that
  performs resolution within a scheduled context.
- **Business date propagation gaps.** In a system with multiple cooperating scheduled
  components, a business date originating in one component may need to be passed to
  another. If the boundary between components is asynchronous (e.g., a Kafka message),
  the business date must travel with the message, not be re-derived from message
  timestamps or system time at the consuming end. Message schemas for inter-component
  communication within a scheduled processing flow must carry the business date
  explicitly.

## Alternatives Considered

**Wall-clock time as the resolution reference.** The resolution function reads the system
clock at execution time and uses that as the resolution date. This is the default
behaviour of any date-sensitive system that does not explicitly model the distinction.
It is rejected because it produces incorrect results for late-running jobs, retries, and
reprocessing — precisely the scenarios that occur when the system is under stress or
recovering from failure. It also makes the system non-deterministic with respect to
time: the same job run at two different wall-clock times against the same business date
may produce different resolution results.

**A shared current-business-date registry.** Rather than passing the business date as
an argument, a shared, mutable registry holds the "current business date" for the
system. Scheduled jobs set this registry at startup; resolution calls read from it
without requiring an explicit argument. This is rejected because it introduces shared
mutable state that is difficult to reason about in concurrent or distributed processing,
and makes reprocessing for a past business date require overriding the registry —
an operational action with wide side effects. It also does not compose: if two jobs
for different business dates run concurrently (a common scenario in catch-up processing
after a failure), the registry can hold only one date. Explicit argument passing
composes correctly; shared state does not.

**Minimum of wall-clock time and business date.** The resolution date is computed as the
earlier of the current wall-clock time and the business date. This prevents future-dated
values from being applied early and produces the correct business date when the job runs
late (since min(late time, business date) = business date). It is rejected on grounds
of obscured intent: the computation is arithmetically equivalent to always using the
business date when the job runs on time or late, which is the normal case, and produces
a surprising result only when a job runs before its business date (pre-processing),
where the wall-clock time would govern. This edge case handling should be explicit if
required, not implied by a min() operation whose purpose is not self-evident.