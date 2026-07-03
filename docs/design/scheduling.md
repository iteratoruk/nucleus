# Technical Design: Scheduling

## Purpose

This concern provides recurring, clustered, JDBC-backed execution of application-defined tasks on
top of Quartz. It governs how a developer adds a scheduled financial process to Nucleus: not by
writing a Quartz `Job`, but by implementing a small `ScheduledTask<T>` interface and registering a
`ScheduledTaskDetails<T>` bean. A single dispatching Quartz job (`QuartzScheduledJob`) resolves the
task by name, deserialises its persisted payload, runs it, and persists the returned payload for the
next fire. This document covers that indirection — the SPI, the registration helper, the JSON payload
round-trip, the Spring autowiring of Quartz jobs, and the clustered store — and the refire and audit
lifecycle around a run. It does not cover which financial processes are scheduled or their timing
semantics; that is a domain concern for a forthcoming scheduling architecture document. It does not
re-document audit event emission — see `docs/design/audit.md`.

## Vocabulary

The task SPI is `iterator.nucleus.schedule.ScheduledTask<T>` — a single method
`run(data: T): ScheduledTaskResult<T>`. `ScheduledTaskResult<T>` carries only an optional `data: T?`
— the payload to carry into the next fire; it holds no status, because a task does not declare its
own outcome. A `run` that returns normally, with or without `data`, is success by definition; failure
is signalled only by throwing. `ScheduledTaskException`, whose `refire: Boolean` flag controls whether
Quartz refires immediately, is the deliberate-failure signal; any other thrown exception is a failure
too but does not trigger an immediate refire.

Registration is carried by `ScheduledTaskDetails<T>` — a value object holding the task's
`beanClass`, its `cronExpression`, the `initialJobData: T`, and the `dataClass`. It is normally
constructed through the reified `scheduledTask(bean, cronExpression, initialJobData)` helper.

`ScheduledTaskConfiguration` is the `@Configuration` that turns registered details into Quartz wiring:
a `List<JobDetail>`, a `List<Trigger>`, and the `SchedulerFactoryBean`. `QuartzScheduledJob` is the
single `org.quartz.Job` implementation that every trigger fires. `AutowiringSpringBeanJobFactory`
is the `SpringBeanJobFactory` that makes Quartz-instantiated jobs Spring-injectable.

The scheduler's own audit vocabulary lives in this package: `ScheduleAuditEventType` — an enum
implementing the audit package's `NucleusAuditEventType` interface, with values
`SCHEDULED_TASK_STARTED` and `SCHEDULED_TASK_FINISHED` — and the two events `ScheduledTaskStartedEvent`
and `ScheduledTaskFinishedEvent`. The finished event carries the internal `ScheduledTaskStatus`
(`SUCCESS` / `FAILURE`) that the dispatcher sets, the measured duration, and a null-safe error
message. `schedule` depends on `audit` one-way; it does not surface any of these types back to the
audit package.

The clustered store schema is Flyway migration `V001__create_quartz_tables.sql` — the standard Quartz
`QRTZ_*` JDBC job-store tables. Store behaviour is configured under the `spring.quartz` block in
`application.yml`. `@EnableScheduling` is declared on `iterator.nucleus.App`.

## Patterns

### Pattern: Task as bean plus registration, never a hand-written Quartz Job

**Problem:** A story needs a process to run on a cron schedule with typed input data. Writing a Quartz
`Job` directly couples the process to Quartz's untyped `JobDataMap`, its instantiation model, and its
lifecycle, and it must be repeated — subtly differently — for every task. The domain semantics of
*which* process runs *when* belong to a scheduling architecture document (forthcoming); this pattern
governs only the mechanical shape.

**Approach:** Implement `ScheduledTask<T>` where `T` is the task's own data type — a `run(data: T)`
that returns a `ScheduledTaskResult<T>`. Make the implementation a Spring `@Component` (or otherwise a
bean) so it can be resolved from the `ApplicationContext` at fire time and can inject its own
collaborators normally. Then expose a `ScheduledTaskDetails<T>` `@Bean` built with the reified helper:

```kotlin
@Bean
fun myTaskDetails(task: MyTask): ScheduledTaskDetails<MyData> =
  scheduledTask(task, "0 0 2 * * ?", MyData(/* initial state */))
```

The helper captures `beanClass` via `ProxyUtils.getUserClass(bean.javaClass)` — unwrapping any Spring
proxy so the stored identity is the real user class — and captures `dataClass` from the reified `T`.
`ScheduledTaskConfiguration` autowires `List<ScheduledTaskDetails<*>>` (Spring collects every such
bean) and, per task, builds a `JobDetail` for `QuartzScheduledJob` identified by the task's bean-class
name in group `scheduledTasks`, and a cron `Trigger` in UTC pointing at that job. You never name a
`Job` class or write cron parsing yourself; you supply a task and its cadence.

**Reference implementation:** `schedule/Schedule.kt` — `ScheduledTask`, `scheduledTask(...)`,
`ScheduledTaskConfiguration.jobDetails` / `triggers`.

**Rules:** Do not implement `org.quartz.Job`; implement `ScheduledTask<T>`. Register through
`scheduledTask(...)` rather than constructing `ScheduledTaskDetails` by hand, so `beanClass` is
proxy-unwrapped and `dataClass` is captured from the reified type. The `JobDetail` identity is the
task's bean-class name — the task must be resolvable from the `ApplicationContext` by that exact class
(`ctx.getBean(Class.forName(name))`), so the class registered in `beanClass` must be the class
registered as a bean. One `ScheduledTask` implementation maps to one `JobDetail`/`Trigger` pair;
Quartz identities in group `scheduledTasks` are keyed by class name, so a second registration of the
same class would collide.

**Pitfalls:** Constructing `ScheduledTaskDetails` directly and passing `bean.javaClass` (rather than
`ProxyUtils.getUserClass`) stores the proxy class name; `Class.forName(name)` at fire time then fails
to resolve the bean. Making the task a prototype or otherwise non-singleton, or registering it under a
different type than `beanClass`, breaks the by-class-name lookup the dispatcher performs.

### Pattern: One dispatching Job with a JSON payload round-trip

**Problem:** Quartz fires `Job` classes with an untyped `JobDataMap`, and task state must survive
across fires (a task that processes "everything since last run" needs to remember where it stopped).
Serialising arbitrary typed task data into Quartz's persistent store, and restoring it as the correct
type on the next fire, must be solved once, not per task.

**Approach:** `QuartzScheduledJob` is the *only* `org.quartz.Job` in the system and every trigger
fires it. On execution it reads two strings from the merged `JobDataMap`: `payloadClass` (the fully
qualified name of the data type) and `payload` (the data as JSON). It resolves the type with
`Class.forName`, deserialises the payload through the shared `ObjectMapper` (see
`docs/design/serialization.md`), resolves the `ScheduledTask` bean by the job's own name from the
`ApplicationContext`, and calls `run(taskData)`. It then writes `result.data` back into the
`JobDataMap` as JSON under `payload`. Because the class is annotated `@PersistJobDataAfterExecution`,
Quartz persists that mutated map after the run, so the next fire deserialises the payload the previous
run produced. The initial `payload`/`payloadClass` entries are seeded by `ScheduledTaskConfiguration.jobDetails`
from `initialJobData` and `dataClass` at registration time.

**Reference implementation:** `schedule/Schedule.kt` — `QuartzScheduledJob.execute`, and the
`usingJobData { put("payloadClass", ...); put("payload", ...) }` seeding in
`ScheduledTaskConfiguration.jobDetails`.

**Rules:** Task data types (`T`) must round-trip through the shared `ObjectMapper` — a
`data class` with a Jackson-constructible shape. `@PersistJobDataAfterExecution` is what carries state
across fires; do not remove it and do not add a competing state-persistence mechanism. Return the
next-fire state as `ScheduledTaskResult.data`; a `null` data serialises to `"null"` and the next run
deserialises `null` into the task — return the intended carry-forward state explicitly. Only the shared
`ObjectMapper` bean may be used for the round-trip so serialisation conventions (see
`docs/design/serialization.md`) hold uniformly.

**Pitfalls:** Storing rich objects directly in the `JobDataMap` instead of a JSON string couples task
state to Quartz's own serialisation and its `useProperties` constraints; the JSON-string round-trip
sidesteps that deliberately. Expecting `run`'s input to reflect out-of-band changes made elsewhere is
wrong — the input is only ever what the previous fire returned (or the seeded `initialJobData` on the
first fire).

### Pattern: Spring-autowired Quartz jobs

**Problem:** Quartz instantiates `Job` classes itself, so a job constructed by Quartz has none of its
Spring dependencies. `QuartzScheduledJob` needs the `ApplicationContext`, the `ObjectMapper`, and the
`AuditService` — all of which would be `null` under raw Quartz instantiation.

**Approach:** `AutowiringSpringBeanJobFactory` extends Spring's `SpringBeanJobFactory` and, in
`createJobInstance`, autowires the freshly created job instance from the `AutowireCapableBeanFactory`
before returning it. `ScheduledTaskConfiguration.schedulerFactory` sets this factory on the
`SchedulerFactoryBean` (`setJobFactory(fac)`), so every job Quartz creates is Spring-injected.

**Reference implementation:** `schedule/Schedule.kt` — `AutowiringSpringBeanJobFactory`, and
`schedulerFactory`'s `setJobFactory(fac)`.

**Rules:** The `SchedulerFactoryBean` must be given `AutowiringSpringBeanJobFactory`; do not construct
the scheduler without it. Any collaborator a dispatching job needs must be a constructor dependency
resolvable by the bean factory.

**Pitfalls:** Omitting the job factory (or using the default `SpringBeanJobFactory`) yields a
`QuartzScheduledJob` whose `ctx`, `om`, and `audit` are `null`, and the first fire fails with an NPE —
not at wiring time but at execution time, which is easy to miss.

### Pattern: Clustered JDBC store with Flyway-owned schema, and refire-controlled failure

**Problem:** Scheduled financial processing must run once per fire across a horizontally scaled
deployment (no double execution when multiple instances are up), survive restarts, and control what
happens when a run fails.

**Approach:** `application.yml` configures `spring.quartz.job-store-type: jdbc` with
`jobStore.isClustered: true`, a `clusterCheckinInterval` of 20s, and the `PostgreSQLDelegate`, giving
Quartz a clustered database job store on Postgres. `spring.quartz.jdbc.initialize-schema: never` hands
schema ownership to Flyway: migration `V001__create_quartz_tables.sql` provides the `QRTZ_*` store
tables, consistent with the project rule that Hibernate/Quartz never DDL and all schema is a Flyway
migration (see `docs/design/persistence.md`). The `SchedulerFactoryBean` is given the application
`DataSource` and the resolved `QuartzProperties`.

Outcome and refire are settled by whether `run` returns or throws. `QuartzScheduledJob.execute`
brackets every dispatch with audit — a `ScheduledTaskStartedEvent` before it runs, and *always* a
`ScheduledTaskFinishedEvent` after, emitted through `AuditService` (see `docs/design/audit.md`). A
`run` that returns normally is recorded `SUCCESS`. Both failure paths are caught: a specific
`catch (ScheduledTaskException)` records `FAILURE` and rethrows `JobExecutionException(msg, e,
e.refire)`, so Quartz refires — or not — per the flag; a following `catch (Exception)` records
`FAILURE` for any other throw and rethrows `JobExecutionException(msg, e, false)`, so Quartz does not
refire immediately. Either way a finished event is emitted with the measured duration and the
exception's (null-safe) message, so a run can no longer complete started-but-never-finished in the
audit trail.

**Reference implementation:** `application.yml` `spring.quartz` block;
`db/migration/V001__create_quartz_tables.sql`; `schedule/Schedule.kt` — `QuartzScheduledJob.execute`
try/catch and `ScheduledTaskException`.

**Rules:** Signal failure by throwing from `run`; to control an immediate refire, throw
`ScheduledTaskException` with the intended `refire` value rather than throwing `JobExecutionException`
yourself. A `run` that returns is success — do not model failure as a returned value; the result no
longer carries a status to hold one. Keep `initialize-schema: never`; the Quartz schema is a Flyway
migration, never Quartz-managed. New Quartz tables or changes to them are new Flyway migrations.

**Pitfalls:** Setting `initialize-schema` to anything but `never` lets Quartz attempt DDL and collide
with Flyway. Expecting a refire from an ordinary thrown exception is wrong — only a
`ScheduledTaskException` with `refire = true` refires immediately; every other exception fails the
fire without an immediate retry, leaving Quartz to apply the trigger's normal next-fire schedule.

## Extension Points

To add a scheduled process, implement `ScheduledTask<YourData>` as a bean and expose a
`ScheduledTaskDetails<YourData>` `@Bean` via `scheduledTask(task, cron, initialData)`. Nothing else in
the wiring changes: `ScheduledTaskConfiguration` already injects `List<ScheduledTaskDetails<*>>` and
builds the `JobDetail`/`Trigger` pair, `QuartzScheduledJob` already dispatches by class name, and the
autowiring factory already Spring-injects the dispatcher. The store, clustering, and audit lifecycle
are fixed infrastructure the new task inherits. Failure semantics are throw-based: return to succeed,
throw `ScheduledTaskException` (choosing the `refire` flag) to fail with refire control, throw
anything else to fail without an immediate refire.

## Relationships

Depends on serialization (`docs/design/serialization.md`) for the payload round-trip via the shared
`ObjectMapper`; on audit (`docs/design/audit.md`) for the started/finished lifecycle events; and on
persistence (`docs/design/persistence.md`) for the Flyway-owns-schema rule that `V001` follows. It
serves a scheduling domain facet (which financial processes are scheduled and their timing semantics)
that has no architecture document yet. The `CLAUDE.md` scheduling paragraph already carries the
headline rule (implement `ScheduledTask<T>`, register `ScheduledTaskDetails`, throw
`ScheduledTaskException` to control refire); this document is its authoritative expansion of the
return-is-success / throw-is-failure model.

## ADR References and Candidates

Three decisions here foreclose reasonable alternatives and are ADR candidates (unwritten):

- Clustered JDBC Quartz job store with a Flyway-owned schema (`initialize-schema: never`), rather than
  a RAM store or Quartz-managed DDL.
- A single dispatching `QuartzScheduledJob` behind a `ScheduledTask` SPI, rather than one hand-written
  Quartz `Job` per scheduled process.
- Task state persisted as a JSON payload in the `JobDataMap` across fires via
  `@PersistJobDataAfterExecution`, rather than storing state in a domain table or in Quartz-native
  serialised objects.

## Open Questions and Findings

The scheduling *domain* facet — which financial processes are scheduled, and their timing semantics
(cadence, calendar rules, catch-up on missed fires) — has no architecture document. This document is
deliberately confined to the infrastructural pattern; the domain reference is forthcoming and should
own those decisions. Do not infer timing semantics from this document.

Three earlier findings are now resolved; they are recorded here only as history. The **ambiguous
success/failure model** is gone: `ScheduledTaskResult` carries only the carry-forward `data`, a task
no longer declares a status, and the model is unambiguous — a `run` that returns is success and a
throw is failure. The **dispatcher catching only `ScheduledTaskException`** is fixed:
`QuartzScheduledJob.execute` now has a specific `catch (ScheduledTaskException)` followed by a general
`catch (Exception)`, so every failure path records `FAILURE`, always emits a `ScheduledTaskFinishedEvent`,
and rethrows as `JobExecutionException`; a run can no longer appear started-but-never-finished. The
**`schedule` ↔ `audit` package cycle** is broken: the scheduler's audit types (`ScheduleAuditEventType`,
`ScheduledTaskStartedEvent`, `ScheduledTaskFinishedEvent`) live in the `schedule` package and depend
on the audit package's `NucleusAuditEventType` interface one-way — the interface-export approach owned
by the audit concern (see `docs/design/audit.md`). The `ScheduledTaskFinishedEvent` error extraction
is null-safe. Audit's half of the former cycle is enforced by ArchUnit:
`PackageDependencyRulesTest."audit must not depend on peer feature packages"`.