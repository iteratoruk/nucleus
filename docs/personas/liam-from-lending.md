# Persona: Liam from Lending

## Role

Liam represents the Business Lending value stream: the upstream client responsible for defining
lending product parameter configuration in Nucleus and instructing Nucleus to open accounts
against that configuration. Once an account is open, Nucleus manages its lifecycle autonomously.

## Type

Active client — product classifier and account initiator.

## Responsibilities

Liam owns the business lending product catalogue and is accountable for ensuring that Nucleus
holds current, correct parameter configuration for every lending product classification in that
catalogue. When a new lending product, facility, or tranche is created, Liam registers the
corresponding classification code in Nucleus and provides the feature configuration appropriate
to that classification. When a customer is approved for a lending product, Liam instructs
Nucleus to open an account against the relevant classification code. From that point, Nucleus
manages the account lifecycle autonomously: interest accrual, scheduled repayment processing,
arrears tracking, and so on. Liam's ongoing role is to consume the lifecycle events Nucleus
emits and act on them within the Lending domain — for example, initiating collections activity
when an account enters arrears, or presenting a renewal offer when a facility approaches its
limit or term.

Liam does not micromanage accounts once opened. It does not instruct Nucleus to apply interest
or process repayments. It trusts Nucleus to do this correctly based on the configuration it
provided at classification time.

## Goals

- Register lending product classification codes and provide parameter configuration so that
  Nucleus can resolve the correct behavioural rules — interest basis, repayment schedule,
  fee structure — for any account in that product family.
- Open accounts against a classification code with confidence that Nucleus will attach the
  account to the correct parameter node and apply the right configuration throughout its
  lifetime.
- Receive timely, accurate lifecycle events from Nucleus — account opened, repayment processed,
  arrears threshold breached, restriction placed or lifted — so that downstream lending
  operations can be triggered correctly without polling or manual intervention.
- Query current and historical account state from Nucleus, including outstanding balance,
  accrued interest, repayment history, and arrears position, to support credit risk and
  customer servicing use cases.
- Close accounts cleanly at natural term end or early settlement, with confidence that Nucleus
  has reconciled all accrued interest, fees, and outstanding principal before confirming closure.

## Constraints

- Lending accounts carry credit risk. The outstanding balance on a lending account is a
  liability to the customer and an asset to the business. Ledger entries must correctly
  reflect the direction of money movement: drawdown increases the customer's liability,
  repayment reduces it. Nucleus must model debit and credit correctly for lending accounts,
  which may differ in sign convention from savings accounts.
- Interest on lending products typically accrues daily and is capitalised or collected on a
  defined schedule. The accrual basis (e.g. actual/365, actual/360) is a parameter defined
  by Liam at classification time. Nucleus applies it; Nucleus does not determine it.
- Arrears states and thresholds are defined by Liam as parameter configuration. Nucleus
  detects and reports arrears conditions based on that configuration; it does not make
  credit decisions.
- Lending accounts may be subject to regulatory reporting obligations (e.g. CAIS, MCOB).
  Nucleus provides the factual account record; Liam is responsible for satisfying reporting
  obligations from that record.
- KYC, AML, and credit assessment preconditions are satisfied upstream of Nucleus. Liam
  asserts that these preconditions are met when instructing Nucleus to open an account.
  Nucleus does not perform or re-verify them.
- Parameter configuration submitted by Liam must be validated by Nucleus at the time of
  registration. Invalid configuration must be rejected with structured, actionable errors.

## Integration Pattern

**Synchronous (REST — outbound from Liam):**

- `PUT /parameters/{classificationCode}` — register or update parameter configuration for a
  lending product classification. Nucleus creates or updates the corresponding node in the
  parameter value hierarchy and validates the configuration provided. Idempotent: re-submitting
  the same classification with the same configuration is safe.
- Account open and close requests.
- Balance, arrears position, and account state queries.

**Asynchronous (Kafka — inbound to Liam):** Lifecycle events published by Nucleus — account
opened, account closed, repayment processed, interest applied, arrears threshold breached,
restriction placed, restriction lifted, ledger entries posted. Liam consumes these to drive
downstream credit risk, collections, and customer journey behaviour.

Liam is an **upstream classifier and initiator**. It defines what a lending product is and
starts accounts. Nucleus owns what happens to those accounts thereafter.

## Interests By Domain Area

**Parameter value hierarchy:** Primary stakeholder. Classification codes for lending products
follow the same hierarchical resolution mechanism as savings. A value defined at `LEND` or
`LEND_BTERM` applies to all accounts in that family unless overridden at a more specific level.
Stories in this domain area that concern lending configuration should include Liam as the
acting persona.

**Account open/close:** Primary stakeholder. Liam initiates both operations. Account closure
for a lending account carries additional complexity: Nucleus must confirm that the outstanding
balance is zero (or that a write-off instruction has been received) before closure is permitted.
This is a constraint that distinguishes Liam's closure stories from Sasha's.

**Account servicing (interest accrual, repayment scheduling, arrears):** Indirect stakeholder.
Liam does not trigger servicing operations. Its interest is in receiving events that confirm
servicing has occurred and in being notified promptly when an account's condition changes in a
way that requires a lending response.

**Balances and ledger entries:** Primary stakeholder for lending-specific balance semantics.
Outstanding principal, accrued interest, and fees are distinct concepts in a lending account
that must be queryable separately. Liam's balance queries are more structured than Sasha's.

**Payments:** Secondary stakeholder. Repayments arrive as inbound payments and must be applied
correctly to a lending account's outstanding obligations. Liam is not the payment initiator
but is directly affected by how Nucleus allocates payment receipts.

**Restrictions and flags:** Reactive stakeholder. A restriction on a lending account may
prevent further drawdown or repayment processing. Liam must be notified promptly, as this
has direct credit risk and customer communication implications.
