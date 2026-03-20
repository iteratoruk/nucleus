# Persona: Maya from Mortgages

## Role

Maya represents the Mortgages value stream: the upstream client responsible for defining mortgage
product parameter configuration in Nucleus and instructing Nucleus to open accounts against that
configuration. Once an account is open, Nucleus manages its lifecycle autonomously.

## Type

Active client — product classifier and account initiator.

## Responsibilities

Maya owns the mortgage product catalogue and is accountable for ensuring that Nucleus holds
current, correct parameter configuration for every mortgage product classification in that
catalogue. When a new mortgage product, rate tier, or tranche is created, Maya registers the
corresponding classification code in Nucleus and provides the feature configuration appropriate
to that classification. When a mortgage offer is accepted and the loan completes, Maya instructs
Nucleus to open an account against the relevant classification code. From that point, Nucleus
manages the account lifecycle autonomously: interest accrual, monthly payment processing,
rate transitions at the end of an introductory period, and so on. Maya's ongoing role is to
consume the lifecycle events Nucleus emits and act on them within the Mortgages domain — for
example, initiating a product transfer offer when a fixed rate period is approaching its end,
or escalating to collections when an account falls into arrears.

Maya does not micromanage accounts once opened. It trusts Nucleus to apply the correct
configuration throughout the lifetime of what are typically long-duration accounts, spanning
years or decades.

## Goals

- Register mortgage product classification codes and provide parameter configuration so that
  Nucleus can resolve the correct behavioural rules — rate type, payment basis, term, early
  repayment charge schedule — for any account in that product family.
- Open accounts against a classification code at the point of completion, with confidence that
  Nucleus will attach the account to the correct parameter node and begin lifecycle management
  immediately.
- Receive timely, accurate lifecycle events from Nucleus — account opened, payment processed,
  rate transition applied, arrears threshold breached, term matured, restriction placed or
  lifted — so that downstream mortgage operations and customer communications can be triggered
  without polling or manual intervention.
- Query current and historical account state from Nucleus, including outstanding balance,
  current rate, accrued interest, payment history, and arrears position, to support customer
  servicing and regulatory reporting use cases.
- Handle rate transitions correctly: when an introductory fixed or tracker rate period ends,
  Nucleus must apply the successor rate as defined by the parameter configuration without
  requiring Maya to re-instruct the account.
- Close accounts at natural term end, on full redemption, or on sale of the secured property,
  with confirmation that Nucleus has reconciled all outstanding principal, accrued interest,
  and applicable early repayment charges before confirming closure.

## Constraints

- Mortgage accounts are secured lending and are subject to a distinct regulatory regime from
  unsecured business lending (primarily FCA MCOB rules). Maya is responsible for satisfying
  these obligations; Nucleus provides the factual account record from which Maya does so.
- Mortgage terms are long — typically two to thirty-five years. The parameter value hierarchy
  must support configuration that remains stable and correctly applied over this duration,
  including through rate transitions that may occur multiple times during the account's life.
  Effective dates on parameter values are therefore particularly significant for Maya.
- Rate types vary: fixed rate, tracker (linked to a reference rate such as the Bank of England
  base rate plus a margin), and standard variable rate. The rate applicable to a given account
  at a given time is a function of the parameter configuration and, for tracker products, an
  external reference rate that Nucleus must be able to receive and apply. The mechanism by
  which external reference rates are communicated to Nucleus is an open architectural question.
- Early repayment charges are a parameter-defined schedule that Nucleus must apply correctly
  when a redemption is processed during a charge period. Maya relies on Nucleus to calculate
  and report these accurately; they are not applied by Maya.
- Monthly payment amounts are calculated by Nucleus based on the outstanding balance, current
  rate, and remaining term. When a rate transition occurs, the monthly payment must be
  recalculated. Maya receives the new payment amount via a lifecycle event and communicates
  it to the customer; it does not instruct Nucleus on what the payment should be.
- KYC, AML, and affordability assessment preconditions are satisfied upstream of Nucleus.
  Maya asserts that these preconditions are met when instructing Nucleus to open an account.
  Nucleus does not perform or re-verify them.
- Parameter configuration submitted by Maya must be validated by Nucleus at the time of
  registration. Invalid configuration must be rejected with structured, actionable errors.

## Integration Pattern

**Synchronous (REST — outbound from Maya):**

- `PUT /parameters/{classificationCode}` — register or update parameter configuration for a
  mortgage product classification. Nucleus creates or updates the corresponding node in the
  parameter value hierarchy and validates the configuration provided. Idempotent: re-submitting
  the same classification with the same configuration is safe.
- Account open and close (redemption) requests.
- Outstanding balance, current rate, payment schedule, and account state queries.

**Asynchronous (Kafka — inbound to Maya):** Lifecycle events published by Nucleus — account
opened, account closed, payment processed, rate transition applied, monthly payment amount
revised, arrears threshold breached, restriction placed, restriction lifted, early repayment
charge calculated, ledger entries posted. Maya consumes these to drive downstream mortgage
operations, customer communications, and regulatory reporting.

Maya is an **upstream classifier and initiator**. It defines what a mortgage product is and
opens accounts at completion. Nucleus owns what happens to those accounts thereafter, including
all rate and payment transitions over the full term.

## Interests By Domain Area

**Parameter value hierarchy:** Primary stakeholder. Mortgage classification codes follow the
same hierarchical resolution mechanism as savings and lending, but effective dates are
especially significant: a rate transition is implemented as a new parameter value with a future
effective date, not as a re-opening or modification of the account. Stories in this domain area
that concern mortgage configuration should include Maya as the acting persona.

**Account open/close:** Primary stakeholder. Account opening corresponds to mortgage
completion — a legally significant event with a defined settlement date. Account closure
corresponds to full redemption and must be accompanied by a final reconciliation of all
outstanding obligations.

**Account servicing (interest accrual, payment processing, rate transitions):** Indirect
stakeholder. Maya does not trigger servicing operations. Its interest is in receiving events
that confirm transitions have been applied correctly and in being notified when account
conditions change in ways that require a mortgage response or customer communication.

**Balances and ledger entries:** Primary stakeholder for mortgage-specific balance semantics.
Outstanding principal, accrued interest, overpayment balance, and early repayment charge
exposure are all distinct queryable concepts for Maya. The balance model for mortgage accounts
is the most complex of the three configurer personas.

**Payments:** Secondary stakeholder. Monthly mortgage payments arrive as inbound payment
receipts and are applied to the account by Nucleus. Maya is not the payment initiator but
depends on correct allocation — principal reduction versus interest settlement — being applied
by Nucleus in accordance with the product configuration.

**Restrictions and flags:** Reactive stakeholder. A restriction on a mortgage account may
prevent payment processing or redemption. Given the secured nature of mortgage lending and the
legal obligations around customer communication, Maya must be notified promptly and with
sufficient detail to determine the appropriate response.
