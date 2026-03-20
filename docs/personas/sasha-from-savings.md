# Persona: Sasha from Savings

## Role

Sasha represents the Savings value stream: the upstream client responsible for defining savings
product parameter configuration in Nucleus and instructing Nucleus to open accounts against that
configuration. Once an account is open, Nucleus manages its lifecycle autonomously.

## Type

Active client — product classifier and account initiator.

## Responsibilities

Sasha owns the savings product catalogue and is accountable for ensuring that Nucleus holds
current, correct parameter configuration for every product classification in that catalogue.
When a new product, issue, or tranche is created, Sasha registers the corresponding
classification code in Nucleus and provides the feature configuration appropriate to that
classification. When a customer takes out a savings product, Sasha instructs Nucleus to open
an account against the relevant classification code. From that point, Nucleus manages the
account lifecycle autonomously: accruals, interest application, maturity handling, and so on.
Sasha's ongoing role is to consume the lifecycle events Nucleus emits and act on them within
the Savings domain — for example, presenting a renewal offer when a fixed-term account matures.

Sasha does not micromanage accounts once opened. It does not instruct Nucleus to apply interest
or trigger maturity processing. It trusts Nucleus to do this correctly based on the configuration
it provided at classification time.

## Goals

- Register product classification codes and provide parameter configuration so that Nucleus can
  resolve the correct behavioural rules for any account in that product family.
- Open accounts against a classification code with confidence that Nucleus will attach the account
  to the correct parameter node and apply the right configuration throughout its lifetime.
- Receive timely, accurate lifecycle events from Nucleus — account opened, interest applied,
  term matured, restriction placed or lifted — so that downstream product behaviour can be
  triggered correctly without polling or manual intervention.
- Query current and historical account state from Nucleus to support customer-facing use cases:
  balance enquiries, transaction history, status checks.
- Close accounts when required, with confidence that Nucleus has concluded all in-flight
  servicing before confirming closure.

## Constraints

- Sasha operates in a regulated savings environment. KYC and AML preconditions are satisfied
  upstream of Nucleus. Sasha asserts that these preconditions are met when instructing Nucleus
  to open an account. Nucleus does not perform or re-verify them.
- Parameter configuration submitted by Sasha must be validated by Nucleus at the time of
  registration. Invalid configuration must be rejected with structured, actionable errors.
  Silent acceptance of configuration that Nucleus cannot honour is not acceptable.
- Configuration does not need to be exhaustive at every classification level. Nucleus resolves
  parameter values hierarchically: a value defined at `SAVE_INAS` applies to all accounts under
  that node unless overridden at a more specific level such as `SAVE_INAS_2026_Q1Q2`. Sasha
  relies on this resolution being deterministic and transparent.
- Sasha has SLA obligations to customers for account opening confirmation. Nucleus must respond
  synchronously to account open requests within acceptable latency bounds.

## Integration Pattern

**Synchronous (REST — outbound from Sasha):**

- `PUT /parameters/{classificationCode}` — register or update parameter configuration for a
  product classification. Nucleus creates or updates the corresponding node in the parameter
  value hierarchy and validates the configuration provided. Idempotent: re-submitting the same
  classification with the same configuration is safe.
- Account open and close requests.
- Balance and account state queries.

**Asynchronous (Kafka — inbound to Sasha):** Lifecycle events published by Nucleus — account
opened, account closed, interest applied, fixed-term matured, restriction placed, restriction
lifted, ledger entries posted. Sasha consumes these to drive downstream product and customer
journey behaviour.

Sasha is an **upstream classifier and initiator**. It defines what a product is and starts
accounts. Nucleus owns what happens to those accounts thereafter.

## Interests By Domain Area

**Parameter value hierarchy:** Primary stakeholder. The classification code and hierarchical
parameter resolution mechanism exists to serve Sasha (and the equivalent configurer personas
Liam and Maya). Stories in this domain area should almost always include Sasha as the acting
persona.

**Account open/close:** Primary stakeholder. Sasha initiates both operations and depends on
correct, timely confirmation. The classification code supplied at open time is the link between
the Savings product catalogue and the Nucleus account record.

**Account servicing (interest accrual, interest application, fixed-term maturity):** Indirect
stakeholder. Sasha does not trigger servicing operations — Nucleus manages these autonomously
based on the parameter configuration Sasha provided. Sasha's interest is in receiving the
events that confirm servicing has occurred correctly.

**Balances and ledger entries:** Secondary stakeholder. Sasha queries this information to
support customer-facing use cases. It is a consumer, not a producer.

**Payments:** Peripheral. Savings accounts may be involved in payments (interest crediting to
a linked account, or a withdrawal instruction), but Sasha is not the payment initiator in most
cases.

**Restrictions and flags:** Reactive stakeholder. Sasha must be notified when a restriction is
placed or lifted on one of its accounts, as this may require a product-level or customer
journey response.
