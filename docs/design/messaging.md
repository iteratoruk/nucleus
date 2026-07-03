# Technical Design: Messaging (Kafka)

## Purpose

This concern covers how Nucleus emits and consumes Kafka messages. It provides the producer/consumer
infrastructure (`KafkaConfiguration`), a topic-name-driven serde pair
(`NucleusSerializer`/`NucleusDeserializer`), the outbound event abstraction and its transactional
publisher (`OutboundEvent`, `KafkaOutboundEventPublisher`), and the composed consumer annotation
(`@TransactionalRetryingKafkaListener`). The boundary of this document is the mechanics of getting a
domain event onto — and off — the wire correctly and transactionally. It does *not* define which
events exist, which are public versus private, or their schemas; that is a domain concern for a
forthcoming messaging architecture document (see Open Questions). It leans on JSON serialization
(`docs/design/serialization.md`) and does not restate it.

## Vocabulary

`OutboundEvent` — the abstract base every produced message extends. It carries only the two wire
fields, `topic` and `key`, both abstract. It holds no audit metadata. It is a plain Jackson POJO; it
is the object serialised onto the wire.

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

## Patterns

### Pattern: Transactional outbound send on commit

**Problem:** A domain operation that runs inside a database transaction needs to publish an event to
Kafka — but the Kafka message must never escape if the transaction rolls back, or downstream
consumers act on state that was never persisted. The send must be gated on the transaction
committing.

**Approach:** Domain code calls `OutboundEventPublisher.publish(event)` and nothing more.
`KafkaOutboundEventPublisher.publish` does exactly one thing: it publishes an intermediary
`OutboundEventReady` application event via `ApplicationEventPublisher`. It does not touch Kafka, and
it raises no audit event.

A *separate* method, `onCommit(ready)`, annotated `@TransactionalEventListener(phase = AFTER_COMMIT)`,
listens for `OutboundEventReady` and performs the real send: `kafkaTemplate.send(topic, key, event)`.
Because the listener is bound to the `AFTER_COMMIT` phase, the Kafka send fires only when the
surrounding transaction commits. If the transaction rolls back, `onCommit` is never invoked and no
message is produced. The producer is idempotent (see the topic-naming pattern), so an at-least-once
send after commit does not duplicate on the broker under retry.

Note the precise mechanism: `publish` does **not** schedule or send the Kafka message. It emits an
application event; the after-commit listener on that event is what sends. This two-step is what gates
the send on commit.

**Reference implementation:** `iterator.nucleus.kafka.KafkaOutboundEventPublisher` — `publish` and
`onCommit` together.

**Rules:**
- Domain code publishes only through `OutboundEventPublisher.publish`; never call `kafkaTemplate.send`
  directly and never publish `OutboundEventReady` yourself.
- The Kafka send must remain in an `AFTER_COMMIT` `@TransactionalEventListener`. Do not move it into
  `publish`.

**Pitfalls:**
- Sending Kafka directly from `publish` (or from a `BEFORE_COMMIT`/`AFTER_COMPLETION` phase) leaks
  messages on rollback — the exact failure this pattern exists to prevent.
- Publishing with no active transaction: `@TransactionalEventListener` by default does not fire
  outside a transaction, so `onCommit` will not run and the message is silently dropped. An
  `OutboundEvent` must be published from within a transactional operation.

### Pattern: Outbound events are plain POJOs, not audit events

**Problem:** The audit machinery already has a rich base class (`AbstractAuditEvent`). The tempting
shortcut is to make `OutboundEvent` extend it and reuse its fields. That shortcut is wrong: an
on-wire Kafka message and an in-process audit event are different concerns with different lifecycles,
and unifying them by inheritance drags Actuator/audit types onto the Kafka wire.

**Approach:** `OutboundEvent` is a standalone abstract class declaring only `topic` and `key`, both
abstract. It extends nothing from the audit or Actuator hierarchy and holds no audit metadata. The
event that goes on the Kafka wire is the `OutboundEvent` subtype itself, serialised by
`NucleusSerializer` through `Serialization.mapper`. Publishing an `OutboundEvent` does not raise an
audit event. Where a happening genuinely warrants *both* a Kafka message and an audit event, that is
an explicit choice at the call site — raise the audit event separately (see `docs/design/audit.md`) —
not a fan-out baked into the publisher.

**Reference implementation:** `iterator.nucleus.kafka.OutboundEvent`.

**Rules:**
- An `OutboundEvent` subclass must **not** extend `AbstractAuditEvent` (or any Spring Actuator audit
  type). It is a plain Jackson POJO carrying only its wire payload.
- If an operation needs both a Kafka message and an audit trail entry, raise the audit event
  explicitly at the call site; do not expect the publisher to do it.

**Pitfalls:**
- Extending `AbstractAuditEvent` to reuse its fields conflates two lifecycles (the in-process audit
  trail and the on-wire message) and drags Actuator/audit types onto the Kafka wire, where they have
  no business being. The type separation is the load-bearing rule here.

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
`PRIVATE_TOPIC_PREFIX` or `PUBLIC_TOPIC_PREFIX` and whose `key` is the partition/ordering key.
Publish it through the injected `OutboundEventPublisher`. Add the topic name as a `const val` to a
topic holder object so `toNewTopics` declares it. If the same happening also warrants an audit trail
entry, raise that audit event separately at the call site (see `docs/design/audit.md`).

To consume it: write a method annotated `@TransactionalRetryingKafkaListener(topics = [...])`, and
register a `TopicMessageTypeMapper` entry mapping that topic (or prefix) to the payload type so
`NucleusDeserializer` can resolve it. No new serde or retry wiring is needed — the existing
infrastructure absorbs the addition once the mapping and annotation are in place.

## Relationships

Messaging depends on **serialization** (`docs/design/serialization.md`) — the serde reuses
`Serialization.mapper`, so all monetary/`BigDecimal` rules apply on the wire. It does *not* depend on
the **audit** concern: the kafka package no longer imports the audit package, and publishing an
`OutboundEvent` raises no audit event. The only relationship to audit is the negative rule that an
`OutboundEvent` must not extend `AbstractAuditEvent` (type separation). Messaging is depended on by
any domain context that publishes or consumes events; those contexts should be governed by a
messaging architecture document that does not yet exist.

CLAUDE.md alignment: CLAUDE.md's "Audit and Kafka are separate channels — choose one" paragraph now
describes the current behaviour — `publish` emits an `OutboundEventReady` application event whose
separate `@TransactionalEventListener(AFTER_COMMIT)` performs the send, and `publish` neither sends
nor audits. No CLAUDE.md edit remains outstanding for this concern.

## ADR References and Candidates

The following decisions foreclose reasonable alternatives and are ADR candidates (unwritten):

1. Transactional outbox-style send via an `AFTER_COMMIT` application-event listener rather than a
   persisted outbox table or a synchronous send.
2. `OutboundEvent` kept as a plain POJO separate from `AbstractAuditEvent` rather than unified
   through inheritance, with no audit fan-out in the publisher.
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

**Resolved:** an earlier version of this publisher raised an audit event for *every* outbound Kafka
message, conflating transport with the audit trail and creating an audit-vs-Kafka divergence on
rollback (audit recorded the raise; Kafka never sent). That fan-out has been removed: `publish` now
emits only the intermediary `OutboundEventReady`, and where a happening warrants both a Kafka message
and an audit event that is an explicit call-site choice. The `KafkaOutboundEventPublisherTest` unit
test pins this — `publish` raises no audit event and defers the send to commit. The separate,
still-valid rule that an `OutboundEvent` must not *extend* `AbstractAuditEvent` (type separation) is
unaffected.