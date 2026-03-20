# Persona: Cameron the Configurer

## Role

Cameron is the abstract configurer persona: the archetype that Sasha, Liam, and Maya each
instantiate. Cameron captures the goals, constraints, and integration pattern that are common
to all upstream value stream clients that configure product behaviour in Nucleus and initiate
accounts against that configuration. Cameron is not used directly in user stories. When
authoring a story that concerns the configuration or account initiation behaviour of a specific
value stream, use Sasha, Liam, or Maya. When authoring a story that concerns the parameter
value hierarchy, account opening, or account closing in general terms — independent of any
specific product domain — Cameron is the appropriate persona.

## Type

Active client — product classifier and account initiator. (Abstract archetype.)

## The Configurer Relationship with Nucleus

All configurer personas share the same fundamental relationship with Nucleus, which is worth
stating once clearly rather than repeating across each instance.

A configurer is upstream of Nucleus. It owns a product catalogue. It knows what its products
promise to customers. It does not know, and does not need to know, how Nucleus fulfils those
promises. The interface between a configurer and Nucleus is narrow and well-defined:

1. The configurer registers product classification codes and attaches parameter configuration
   to nodes in the parameter value hierarchy.
2. The configurer instructs Nucleus to open an account against a classification code.
3. Nucleus attaches the account to the corresponding node, resolves the applicable
   configuration, and begins managing the account lifecycle autonomously.
4. Nucleus emits lifecycle events. The configurer consumes them and acts on them within
   its own domain.
5. The configurer instructs Nucleus to close the account when appropriate.

At no point does the configurer instruct Nucleus on how to service an account. The
configuration provided at step 1 is the complete expression of the configurer's intent.
Steps 3 and 4 are entirely Nucleus's responsibility. The configurer trusts Nucleus to
deliver the product promise that the configuration encodes.

This separation is the core architectural principle of the configurer relationship. Stories
that violate it — by having a configurer instruct Nucleus to apply interest, trigger a
maturity, or make a servicing decision — are out of scope for a configurer persona and
represent a design failure.

## Shared Goals

Every configurer persona shares the following goals. These are not repeated in the
individual persona files; those files document only what is distinctive about each
value stream.

- Register product classification codes and provide parameter configuration through
  `PUT /parameters/{classificationCode}`, so that Nucleus can resolve the correct
  behavioural rules for any account in that product family.
- Rely on hierarchical parameter resolution: configuration defined at a parent node
  (e.g. `SAVE_INAS`) applies to all descendant accounts unless overridden at a more
  specific level (e.g. `SAVE_INAS_2026_Q1Q2`). This resolution must be deterministic,
  transparent, and consistent across the account lifetime.
- Open accounts against a classification code and receive synchronous confirmation that
  the account is active and correctly configured before the customer journey proceeds.
- Receive a complete and reliable stream of lifecycle events from Nucleus so that
  downstream product behaviour can be triggered by event consumption rather than by
  polling or re-querying account state.
- Query current and historical account state from Nucleus to support customer-facing
  use cases without needing to maintain a separate state store.
- Close accounts with confidence that Nucleus has concluded all in-flight servicing and
  that the final account state is correct before closure is confirmed.

## Shared Constraints

Every configurer persona shares the following constraints.

**KYC and AML preconditions are upstream of Nucleus.** The configurer asserts that
regulatory preconditions for account opening have been satisfied. Nucleus does not verify
them. A story that places KYC or AML logic inside Nucleus is out of scope.

**Parameter configuration must be validated at registration time.** Invalid configuration
submitted via `PUT /parameters/{classificationCode}` must be rejected with structured,
actionable errors. Silent acceptance of configuration that Nucleus cannot honour is not
acceptable. The configurer has SLA obligations to customers that depend on knowing at
configuration time, not at account opening time, whether the configuration is valid.

**The `PUT /parameters/{classificationCode}` operation is idempotent.** Re-submitting
a classification code with the same configuration must be safe. Re-submitting with
different configuration must update the node and carry an effective date. The implications
of a configuration change on accounts already attached to the affected node is a domain
question to be resolved in the parameter value hierarchy architecture — but the operation
itself must never be unsafe to repeat.

**Account opening is synchronous and must meet latency SLAs.** Configurer value streams
have downstream customer journey dependencies on account opening confirmation. Nucleus
must respond to account open requests synchronously within a defined latency bound. The
specific bound is a product of the configurer's SLA with its customers and is documented
in the individual persona file where it is more constrained than the general case.

**Nucleus owns the account lifecycle after opening.** Once an account is open, the
configurer's active role is complete. The configurer does not trigger servicing events,
does not instruct Nucleus on rate or payment decisions, and does not manage the account
toward closure except by issuing an explicit close instruction when appropriate. Stories
in which a configurer micromanages account lifecycle are design failures, not backlog items.

**Classification codes are the configurers' primary key in Nucleus.** Every account,
every event, and every parameter node is associated with a classification code. The code
is the configurer's handle on its product catalogue within Nucleus. It must be present
and consistent on all events and records that the configurer needs to consume or query.

## Shared Integration Pattern

**Synchronous (REST — outbound from Cameron):**

- `PUT /parameters/{classificationCode}` — register or update parameter configuration
  for a product classification. Idempotent. Returns validation errors synchronously if
  the configuration is invalid.
- `POST /accounts` (or equivalent) — open an account against a classification code.
  Returns synchronous confirmation of account status and identifier.
- `DELETE /accounts/{accountId}` (or equivalent) — close an account. Returns synchronous
  confirmation that closure has been initiated or completed, or a structured error if
  preconditions for closure are not met.
- Account state and balance queries — return current and historical account state,
  including status, balance, ledger entries, and applied restrictions.

**Asynchronous (Kafka — inbound to Cameron):**

The configurer subscribes to lifecycle event topics published by Nucleus. The minimum
set of events that every configurer persona must be able to consume:

- Account opened
- Account closed
- Interest accrued (end of accrual period)
- Interest applied (posted to the account balance)
- Restriction placed
- Restriction lifted
- Ledger entry posted

Events specific to individual product domains — fixed-term maturity, rate transition,
early repayment charge calculated, arrears threshold breached — are documented in the
individual persona files for the relevant configurer.

Every event must carry: the account identifier, the classification code, the event type,
the effective timestamp, and sufficient domain detail for the configurer to act on the
event without a follow-up query to Nucleus. An event that requires a secondary query to
be useful is an incomplete event.

## How to Use Cameron in Story Sessions

Load Cameron as the persona when:

- The story concerns the parameter value hierarchy in general: node creation, hierarchical
  resolution, effective-dated configuration updates, validation behaviour. These stories
  are about the mechanism, not the product domain, and Cameron is the correct actor.
- The story concerns account opening or closing behaviour that is common to all value
  streams: the opening confirmation response, the idempotency of a duplicate open request,
  the precondition enforcement for closure. These stories should not be anchored to Savings
  or Lending specifically unless the behaviour differs by product domain.
- The story concerns the event contract that all configurer personas depend on: event
  schema, event completeness, event ordering guarantees. Cameron is the consumer whose
  requirements define the minimum contract.

Load Sasha, Liam, or Maya when:

- The story concerns product-domain-specific behaviour: savings interest accrual, mortgage
  rate transitions, lending arrears thresholds. The specific product context is material
  to the acceptance criteria.
- The story concerns a constraint or goal that is distinctive to that value stream and
  not shared by the others.
- The acceptance criteria reference product-specific terminology from the value stream's
  domain that would be incorrect or misleading if expressed in Cameron's generic terms.

When in doubt, prefer the specific persona. Cameron is a reference document and a story
authoring convenience, not a default.

## Instances

| Persona | Value Stream | Distinctive Concerns |
|---|---|---|
| Sasha from Savings | Savings | Instant access and fixed-term savings products; interest accrual and application; fixed-term maturity. |
| Liam from Lending | Business Lending | Unsecured business lending; repayment scheduling; arrears detection; debit/credit polarity. |
| Maya from Mortgages | Mortgages | Secured lending; long-duration accounts; rate transitions; tracker rates; ERC schedules; payment recalculation. |
