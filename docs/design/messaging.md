# Technical Design: Messaging (Kafka)

## Purpose

This concern covers how Nucleus emits and consumes Kafka messages. It provides the producer/consumer
infrastructure (`KafkaConfiguration`), a topic-name-driven serde pair
(`NucleusSerializer`/`NucleusDeserializer`), the outbound event abstraction and its transactional
publisher (`OutboundEvent`, `KafkaOutboundEventPublisher`), and the composed consumer annotation
(`@TransactionalRetryingKafkaListener`). The boundary of this document is the mechanics of getting a
domain event onto — and off — the wire correctly and transactionally. It does *not* define which
events exist, which are public versus private, or their schemas; that is a domain concern for a
forthcoming messaging architecture document (see Open Questions). It leans on two other concerns and
does not restate them: audit event raising (`docs/design/audit.md`) and JSON serialization
(`docs/design/serialization.md`).

## Vocabulary

`OutboundEvent` — the abstract base every produced message extends. It carries the wire fields
(`topic`, `key`) *and* just enough audit metadata (`auditType`, `auditPrincipal`, `auditTimestamp`,
`auditData`) for the publisher to raise a separate audit event. It is a plain Jackson POJO; it is the
object serialised onto the wire.

`OutboundEventPublisher` — the single-method interface (`publish(event: OutboundEvent)`) domain code
depends on. `KafkaOutboundEventPublisher` is its only implementation and also hosts the
after-commit listener.

`OutboundEventReady` — an intermediary Spring `ApplicationEvent` (a `data class` wrapping the
`OutboundEvent`) used purely to defer the Kafka send to transaction commit.

`NucleusSerializer` / `NucleusDeserializer` — the Kafka value serde beans. Both extend
`TopicTypeResolver` and mix in `NucleusSerializationBase`, resolving the Java payload type from the
topic name and delegating to `Serialization.mapper` (see `docs/design/serialization.md`).

`TopicMessageTypeMapper` — a `fun interface` mapping a topic name to a payload `Class<*>`.
`RegexTopicMessageTypeMapper` is the supplied implementation: it matches on topic *prefix* via a
regex. `TopicTypeResolver` fans a lookup across all registered mappers and fails loudly if none match.

`@TransactionalRetryingKafkaListener` — the composed meta-annotation (`@KafkaListener` +
`@Transactional` + `@RetryableTopic`) that every consumer method must carry.

`KafkaConstants` — `PRIVATE_TOPIC_PREFIX` (`nucleus.private.`) and `PUBLIC_TOPIC_PREFIX`
(`nucleus.public.`).

`KafkaConfigurationProperties` / `KafkaRetryConfigurationProperties` — bind the
`nucleus.defaults.kafka` block (`number-of-partitions`, `replication-factor`, `retry.*`).

`NucleusAuditEventType` — the audit event type enum (defined under the audit concern) referenced by
each `OutboundEvent.auditType`.

## Patterns

### Pattern: Transactional outbound fan-out — audit now, Kafka on commit

**Problem:** A domain operation that runs inside a database transaction needs to (a) leave an audit
trail of the event and (b) publish that event to Kafka — but the Kafka message must never escape if
the transaction rolls back, or downstream consumers act on state that was never persisted. The two
obligations have different lifecycles and must not be conflated.

**Approach:** Domain code calls `OutboundEventPublisher.publish(event)` and nothing more.
`KafkaOutboundEventPublisher.publish` does exactly two things, in order:

1. It builds a `GenericAuditEvent` from the event's `auditType`, `auditPrincipal`, `auditData`, and
   `auditTimestamp` and hands it to `AuditService.publishAuditEvent`. This happens *synchronously
   within `publish`*, independent of the transaction outcome — the audit trail records that the
   event was raised regardless of whether the transaction later commits or rolls back.
2. It publishes an intermediary `OutboundEventReady` application event via
   `ApplicationEventPublisher`.

A *separate* method, `onCommit(ready)`, annotated `@TransactionalEventListener(phase = AFTER_COMMIT)`,
listens for `OutboundEventReady` and performs the real send: `kafkaTemplate.send(topic, key, event)`.
Because the listener is bound to the `AFTER_COMMIT` phase, the Kafka send fires only when the
surrounding transaction commits. If the transaction rolls back, `onCommit` is never invoked and no
message is produced. The producer is idempotent (see the topic-naming pattern), so an at-least-once
send after commit does not duplicate on the broker under retry.

Note the precise mechanism: `publish` does **not** schedule or send the Kafka message. It emits an
application event; the after-commit listener on that event is what sends. This two-step is what makes
the audit-vs-Kafka split and the commit gating both hold at once.

**Reference implementation:** `iterator.nucleus.kafka.KafkaOutboundEventPublisher` — `publish` and
`onCommit` together.

**Rules:**
- Domain code publishes only through `OutboundEventPublisher.publish`; never call `kafkaTemplate.send`
  directly and never publish `OutboundEventReady` yourself.
- The Kafka send must remain in an `AFTER_COMMIT` `@TransactionalEventListener`. Do not move it into
  `publish`.
- The audit raise stays inside `publish` (before commit, unconditional). Do not gate it on the
  transaction.

**Pitfalls:**
- Sending Kafka directly from `publish` (or from a `BEFORE_COMMIT`/`AFTER_COMPLETION` phase) leaks
  messages on rollback — the exact failure this pattern exists to prevent.
- Assuming a rollback also un-does the audit event: it does not, and that is intentional. The audit
  record is of the *attempt to raise* the event, not of a committed side effect.
- Publishing with no active transaction: `@TransactionalEventListener` by default does not fire
  outside a transaction, so `onCommit` will not run and the message is silently dropped. An
  `OutboundEvent` must be published from within a transactional operation.

### Pattern: Outbound events are plain POJOs, not audit events

**Problem:** Every outbound event needs audit metadata, and the audit machinery already has a rich
base class (`AbstractAuditEvent`). The tempting shortcut is to make `OutboundEvent` extend it and
reuse the fields. That shortcut is wrong.

**Approach:** `OutboundEvent` is a standalone abstract class. It declares `topic`, `key`, and
`auditType` as abstract members and `auditPrincipal`/`auditTimestamp`/`auditData` as `open` members
with defaults. It extends nothing from the audit or Actuator hierarchy. The publisher reads the
`audit*` fields off the event to *construct* a `GenericAuditEvent` — a translation, not an
inheritance. The event that goes on the Kafka wire is the `OutboundEvent` subtype itself, serialised
by `NucleusSerializer` through `Serialization.mapper`.

**Reference implementation:** `iterator.nucleus.kafka.OutboundEvent`.

**Rules:**
- An `OutboundEvent` subclass must **not** extend `AbstractAuditEvent` (or any Spring Actuator audit
  type). It is a plain Jackson POJO.
- It carries only the wire payload plus the minimal audit metadata the publisher needs to raise a
  separate audit event.

**Pitfalls:**
- Extending `AbstractAuditEvent` to avoid re-declaring `auditType`/`auditData` conflates two
  lifecycles (the in-process audit trail and the on-wire message) and drags Actuator/audit types onto
  the Kafka wire, where they have no business being. This is the seam between the two concerns: the
  event holds audit *data*, the publisher owns audit *behaviour*. See `docs/design/audit.md`.

### Pattern: Topic-name-driven serde

**Problem:** Kafka value (de)serialization is invoked with only a topic name and raw bytes/object —
there is no ambient type information. Nucleus needs each topic's payloads serialised with the single
project `ObjectMapper`, and deserialised back to the correct concrete type.

**Approach:** `NucleusSerializer` and `NucleusDeserializer` resolve the payload type from the topic
name. Resolution runs through `TopicTypeResolver`, which asks each registered `TopicMessageTypeMapper`
in turn; the supplied `RegexTopicMessageTypeMapper` turns each configured `topic → Class` entry into a
prefix regex (`^<escaped-topic>.*$`) and returns the first match. The resolved type is cached per
topic in a `ConcurrentHashMap`. If the type is `String`, a plain `StringSerializer`/`StringDeserializer`
handles it; otherwise the work delegates to `Serialization.mapper` (`writeValueAsBytes` /
`readValue(bytes, type)`) — reusing the one configured mapper, so `BigDecimal`-as-string and the other
serialization rules in `docs/design/serialization.md` hold on the wire automatically.

**Reference implementation:** `iterator.nucleus.kafka.NucleusSerializationBase` (the serialize/
deserialize logic), with `RegexTopicMessageTypeMapper` and `TopicTypeResolver`.

**Rules:**
- For every topic whose payload is not a bare `String`, register a `TopicMessageTypeMapper` bean (or
  entry) mapping the topic (or its prefix) to the payload `Class`.
- Never introduce a second `ObjectMapper` for Kafka. The serde already routes through
  `Serialization.mapper`.

**Pitfalls:**
- Forgetting to map a topic: `TopicTypeResolver.resolveType` calls `checkNotNull` and throws
  `"Topic <name> has no type mapping!"` at first (de)serialization. There is no silent fallback to a
  default type.
- Relying on the regex being anchored to a full topic name — it is a *prefix* match, so a mapping key
  of `nucleus.private.account` also matches `nucleus.private.account.opened`. Choose mapping keys with
  that prefix semantics in mind.

### Pattern: Consumers use the composed listener annotation

**Problem:** Every consumer needs the same three properties — it is a Kafka listener, it runs inside a
transaction, and it retries with a uniform backoff policy — and hand-assembling that on each method
invites drift.

**Approach:** Annotate the consumer method with `@TransactionalRetryingKafkaListener(topics = [...])`.
The annotation meta-composes `@KafkaListener`, `@Transactional`, and `@RetryableTopic`. Its `attempts`
and `backoff` (delay, multiplier, max-delay) default to SpEL expressions bound to
`nucleus.defaults.kafka.retry.*`, so the retry policy is configured once per profile rather than per
listener. It excludes `IllegalArgumentException` from retry by default — a malformed message is a
poison pill, not a transient fault, and retrying it is pointless. Retry topics require
`@EnableKafkaRetryTopic` (on `KafkaConfiguration`) and Spring Retry (`@EnableRetry` on `App`), both
already present.

**Reference implementation:** `iterator.nucleus.kafka.TransactionalRetryingKafkaListener`.

**Rules:**
- Annotate consumers with `@TransactionalRetryingKafkaListener`, never raw `@KafkaListener`, so
  transactional and retry semantics stay uniform across the service.
- Tune retry behaviour through `nucleus.defaults.kafka.retry.*` in `application.yml`, not by
  overriding the annotation per method.

**Pitfalls:**
- Adding a bespoke `@Retryable`/`@RetryableTopic` with local backoff numbers reintroduces the drift
  this annotation removes.
- Expecting an `IllegalArgumentException` to be retried — it is excluded. If a consumer should retry
  on a validation-style failure, it must not surface it as `IllegalArgumentException`.

### Pattern: Topic naming, idempotent producer, and topic declaration

**Problem:** Topics need consistent names that signal their visibility, a producer that does not
duplicate on retry, and a low-ceremony way to declare them to the broker.

**Approach:** Topic names are constructed from one of two prefixes in `KafkaConstants`:
`nucleus.private.` for internal, service-owned topics and `nucleus.public.` for topics that are part
of a published contract. The producer factory sets `ENABLE_IDEMPOTENCE=true` and `acks=all`, so the
after-commit send is safe to redeliver without producing duplicates. To declare topics, hold their
names as `const val String` properties on an object and pass that object to
`KafkaConfigurationUtils.toNewTopics(obj, numberOfPartitions, replicationFactor)`; it reflects over
the object's `const String` members and produces a `KafkaAdmin.NewTopics` bean. Partition and
replication counts come from `KafkaConfigurationProperties` (`nucleus.defaults.kafka`): 10/3 by
default, overridden to 1/1 under the `api-test` profile (with tiny retry values) so tests run against
a single-broker container.

**Reference implementation:** `iterator.nucleus.kafka.KafkaConstants`,
`iterator.nucleus.kafka.KafkaConfigurationUtils.toNewTopics`, and the `producerFactory` bean in
`KafkaConfiguration`.

**Rules:**
- Name every topic with `PRIVATE_TOPIC_PREFIX` or `PUBLIC_TOPIC_PREFIX`; the choice is a deliberate
  visibility statement, not cosmetic.
- Declare topics by adding `const val` names to a holder object and feeding it to `toNewTopics`; do
  not hand-build `NewTopic` lists.

**Pitfalls:**
- Declaring a topic name as a non-`const` `val` or a non-`String`: `toNewTopics` filters to
  `isConst` `String` members and will silently skip it, so the topic is never declared.

## Extension Points

To emit a new event: define an `OutboundEvent` subclass whose `topic` is built from
`PRIVATE_TOPIC_PREFIX` or `PUBLIC_TOPIC_PREFIX`, set its `key` (the partition/ordering key) and its
`auditType` (a `NucleusAuditEventType` value — add one under the audit concern if none fits), and
populate `auditData`/`auditPrincipal` as needed. Publish it through the injected
`OutboundEventPublisher`. Add the topic name as a `const val` to a topic holder object so
`toNewTopics` declares it.

To consume it: write a method annotated `@TransactionalRetryingKafkaListener(topics = [...])`, and
register a `TopicMessageTypeMapper` entry mapping that topic (or prefix) to the payload type so
`NucleusDeserializer` can resolve it. No new serde or retry wiring is needed — the existing
infrastructure absorbs the addition once the mapping and annotation are in place.

## Relationships

Messaging depends on **audit** (`docs/design/audit.md`) — the publisher raises a `GenericAuditEvent`
through `AuditService`, and the `OutboundEvent`/`AbstractAuditEvent` separation is the load-bearing
seam between the two concerns — and on **serialization** (`docs/design/serialization.md`) — the serde
reuses `Serialization.mapper`, so all monetary/`BigDecimal` rules apply on the wire. It is depended on
by any domain context that publishes or consumes events; those contexts should be governed by a
messaging architecture document that does not yet exist.

Candidate CLAUDE.md edits: the current one-liner ("`KafkaOutboundEventPublisher.publish` … schedules
the Kafka send for `AFTER_COMMIT`") is a compression that misattributes the send to `publish`. The
precise statement is that `publish` raises the audit event and emits an `OutboundEventReady`
application event, and a separate `@TransactionalEventListener(AFTER_COMMIT)` performs the send. Worth
tightening when convention text is next revised.

## ADR References and Candidates

The following decisions foreclose reasonable alternatives and are ADR candidates (unwritten):

1. Transactional outbox-style send via an `AFTER_COMMIT` application-event listener rather than a
   persisted outbox table or a synchronous send.
2. `OutboundEvent` kept separate from `AbstractAuditEvent` (audit data carried, audit behaviour
   owned by the publisher) rather than unified through inheritance.
3. Topic-name → payload-type resolution via prefix regex (`RegexTopicMessageTypeMapper`) rather than
   type headers or a schema registry.
4. `@TransactionalRetryingKafkaListener` mandated as the single consumer annotation, binding retry
   policy to `nucleus.defaults.kafka.retry.*` and excluding `IllegalArgumentException`.
5. The `nucleus.private.` / `nucleus.public.` topic-prefix visibility scheme.

## Open Questions and Findings

The messaging *domain* — which events are public versus private, event schemas, and their versioning
— is not modelled anywhere and is deliberately out of scope here. It is a forthcoming architecture
reference; this document governs only the transport mechanics. The `PRIVATE_TOPIC_PREFIX` /
`PUBLIC_TOPIC_PREFIX` distinction exists in code as a naming convention, but nothing yet enforces or
defines the visibility contract it implies.

Finding: the audit event is raised inside `publish` unconditionally, before the transaction resolves,
while the Kafka send is gated on commit. This is by design, but it means audit and Kafka can disagree
on a rollback (audit records the raise; Kafka never sends). That divergence should be acknowledged in
the messaging architecture document when written, so consumers of the audit trail understand that an
audit event is evidence of an *attempt*, not of a delivered message.

**Finding (design defect — architecture/story session): auditing every Kafka message is not the
intended model.** `KafkaOutboundEventPublisher.publish` raises an audit event for *every* outbound
Kafka message. This conflates two things that should be alternatives: a happening should be *either* an
audit event (a light, async application-context event — see `docs/design/audit.md`) *or* a Kafka event
(typically business-critical processing or a domain-event publication), not automatically both. Making
every Kafka publication also an audit event is heavyweight and pollutes the audit trail with transport
concerns. The intended correction, to be worked in an architecture/story session, is to decouple the
two: publishing a Kafka event should not implicitly raise an audit event, and where a happening
genuinely warrants both, that is an explicit choice at the call site rather than a fan-out baked into
the publisher. This finding concerns the *fan-out policy* only; the separate and still-valid rule that
an `OutboundEvent` must not *extend* `AbstractAuditEvent` (the type separation, above) is unaffected
and remains in force. Note that CLAUDE.md's "Audit vs Kafka — keep them separate" convention currently
describes this fan-out as intended behaviour: it accurately reflects the present code and is therefore
left unchanged for now, but it is under review and should be revised when this correction is made.