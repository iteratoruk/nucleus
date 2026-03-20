# Persona: Parker from Payments

## Role

Parker represents the Faster Payments scheme rail provider: the external counterparty
through which Nucleus submits outbound payment instructions and from which Nucleus receives
inbound payment notifications, settlement confirmations, and scheme-level exception messages.

## Type

External counterparty — bidirectional scheme participant operating under ISO 20022 messaging
standards and Faster Payments Scheme rules.

## Responsibilities

Parker is not a client of Nucleus in the way that Sasha, Liam, or Maya are. Parker does not
consume Nucleus's API, configure product behaviour, or observe the internal lifecycle of
accounts. Parker is an external infrastructure provider operating a regulated payment scheme,
and the relationship between Nucleus and Parker is governed by scheme participation rules,
bilateral connectivity agreements, and the ISO 20022 message standard — not by Nucleus's
internal API conventions.

Parker's responsibilities are symmetric with Nucleus's: Parker accepts outbound payment
instructions from Nucleus, routes them to the beneficiary's bank, and returns settlement
outcomes. Parker submits inbound payment notifications to Nucleus for credit to the
nominated account, and expects Nucleus to confirm acceptance or rejection. Parker initiates
scheme-level exception processes — recalls, returns, investigations — and expects Nucleus
to respond within scheme-mandated timeframes.

Parker is indifferent to the internal structure of Nucleus. Parker does not know or care
whether a payment credits a savings account or a mortgage account, whether the account is
restricted, or whether the account is managed by Sasha or Maya. Parker knows scheme
identifiers, ISO 20022 message structures, and settlement obligations.

## Goals

Parker's goals are stated from the perspective of what Parker requires of Nucleus as a
scheme participant, since this is what shapes Nucleus's design obligations:

- Receive well-formed ISO 20022 payment initiation messages from Nucleus (pacs.008 for
  credit transfers) that are complete, valid against the scheme's message specification,
  and submitted within the scheme's processing window.
- Return settlement confirmation or rejection messages (pacs.002) to Nucleus promptly,
  with scheme transaction identifiers that Nucleus can use to update its internal payment
  record and emit the appropriate lifecycle events.
- Submit inbound credit transfer notifications (pacs.008) to Nucleus for nominated
  accounts, and receive a timely acceptance (pacs.002 accepted) or rejection (pacs.002
  rejected) response from Nucleus within the scheme's response window.
- Initiate recall requests (camt.056) and return requests against previously settled
  payments, and receive a response from Nucleus within scheme-mandated timeframes.
- Receive and respond to payment investigation requests (camt.026, camt.029) from Nucleus
  relating to unmatched or disputed payments, within scheme-mandated timeframes.
- Maintain a reliable, ordered message exchange with Nucleus such that no message is
  silently lost, duplicated, or processed out of sequence.

## Constraints

- The Faster Payments Scheme operates under scheme rules that are not negotiable and not
  configurable by Nucleus. Message formats, processing windows, response timeframes, and
  exception handling procedures are defined by the scheme. Nucleus must conform to them.
  Where scheme rules conflict with internal Nucleus design preferences, the scheme rules
  take precedence.
- ISO 20022 messages are XML. The message structure, field constraints, and code lists
  are defined by the ISO 20022 message definition reports and by the scheme's implementation
  guide. Nucleus must produce and consume messages that are valid against these definitions.
  Nucleus's internal data model is irrelevant to Parker; what matters is what arrives in
  the XML.
- Every message exchanged with Parker carries a scheme transaction identifier (EndToEndId,
  TxId, or equivalent depending on message type) that Nucleus must preserve, echo, and
  store against its internal payment record. This identifier is the reconciliation key
  for Parker and for Alex. It must not be generated arbitrarily or discarded after
  transmission.
- Parker operates within defined processing windows. Outbound payment instructions
  submitted outside the scheme's processing window will be queued or rejected depending
  on scheme rules at the time. Nucleus must be aware of processing window boundaries and
  handle out-of-window submissions appropriately.
- Inbound payments from Parker require a response within a scheme-mandated window,
  typically seconds. If Nucleus cannot process an inbound credit transfer and respond
  within that window, it must reject the message. Delayed acceptance is not permitted
  by the scheme. This constraint has direct implications for the latency and reliability
  requirements of Nucleus's inbound payment processing path.
- Scheme exception messages — recalls, returns, investigations — carry their own
  timeframe obligations. A recall request to which Nucleus does not respond within the
  scheme deadline is treated by Parker as a consent to return. Nucleus must not allow
  scheme exception messages to queue unprocessed.
- Parker communicates with Nucleus over a connectivity layer (typically a scheme-provided
  gateway or a SWIFT-based channel) that is distinct from Nucleus's REST API. The
  integration architecture for Parker is therefore different in kind from all other personas:
  it involves a message exchange protocol, not an HTTP API, and the details of that
  connectivity are an architectural concern to be resolved in an ADR before any payment
  stories are implemented.

## Integration Pattern

**Bidirectional message exchange (ISO 20022 XML over scheme connectivity):**

Outbound from Nucleus to Parker:
- `pacs.008` — FI to FI customer credit transfer (outbound payment instruction)
- `camt.026` — unable to apply (investigation request)
- `camt.029` — resolution of investigation (response to Parker's investigation)
- `pacs.004` — payment return (responding to a Parker-initiated return request)

Inbound from Parker to Nucleus:
- `pacs.008` — FI to FI customer credit transfer (inbound credit notification)
- `pacs.002` — FI to FI payment status report (settlement confirmation or rejection)
- `camt.056` — FI to FI payment cancellation request (recall request)
- `camt.029` — resolution of investigation (Parker's response to Nucleus investigation)

This integration pattern is categorically different from all other personas. There is no
REST, no Kafka subscription, and no Nucleus-internal event model visible to Parker. The
boundary between Nucleus and Parker is an XML message exchange governed entirely by
scheme rules and the ISO 20022 standard.

The internal translation between Parker's ISO 20022 messages and Nucleus's internal payment
domain model — and the reverse — is a Nucleus responsibility. Parker is never adapted to
Nucleus; Nucleus adapts to Parker.

## Interests By Domain Area

**Payments:** Sole and total stakeholder from an external perspective. The entire payment
domain in Nucleus exists to enable scheme participation with Parker. Every design decision
in the payment domain must be evaluated against whether it produces correct, timely, and
scheme-compliant behaviour toward Parker.

**Ledger entries:** Indirect interest. Parker does not see Nucleus's ledger. However, the
settlement of a payment by Parker must produce ledger entries in Nucleus that correctly
reflect the movement of funds. The ledger entry events that Alex consumes are the internal
consequence of Parker's settlement confirmations. The chain — Parker settles, Nucleus
posts ledger entries, Alex reconciles — must be reliable and complete.

**Account state:** Indirect interest. Parker submits inbound payments to a sort code and
account number. Nucleus must resolve that to an internal account and determine whether it
is in a state that permits credit. Parker does not know the internal account state; it
knows only whether Nucleus accepted or rejected the inbound message. The acceptance or
rejection must be based on current account state at the moment of processing.

**Restrictions and flags:** Indirect interest. A restriction that prevents credit to an
account will cause Nucleus to reject an inbound payment from Parker. Parker receives the
rejection but not the reason. The internal reason — restriction in force — is an Eddie and
Alex concern, not a Parker concern. However, the rejection must be correctly coded in the
pacs.002 response so that Parker can route the return correctly under scheme rules.

**All other domain areas:** No direct interest. Parker is unaware of account servicing,
product configuration, reporting, or reconciliation. Parker sees only the message exchange
surface.
