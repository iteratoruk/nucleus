# Persona: Casey the Customer

## Role

Casey represents the end customer: the individual whose financial products are managed by
Nucleus, whose money moves through its ledger, and in whose name accounts are opened, serviced,
and closed. Casey never interacts with Nucleus directly. Casey is the reason Nucleus exists.

## Type

Indirect beneficiary — the human whose interests are proxied by every other persona in the
system.

## Responsibilities

Casey has no technical responsibilities in relation to Nucleus. Casey does not call APIs,
consume events, or configure anything. Casey's responsibilities are those of a bank customer:
to agree to product terms, to make payments when due, to notify the bank of changes in
circumstance, and to exercise rights under consumer protection regulation when things go wrong.

Casey is included in the persona cast not as an integration point but as a grounding
discipline. When a story's value statement is unclear, asking "what changes for Casey?" often
resolves it. When an acceptance criterion is ambiguous, asking "how would Casey know this had
happened?" often sharpens it. When a design decision is contested, asking "what is the risk
to Casey if this is wrong?" often settles the priority.

Without Casey, the value statements of stories authored for Sasha, Liam, Maya, and Eddie
risk becoming purely operational — correct from a systems integration perspective but
disconnected from the financial consequences for the person whose money is at stake.

## Goals

Casey's goals are stated as the financial and experiential outcomes Casey depends on, since
these are what the system must ultimately deliver even though Casey never touches it:

- To have accounts opened promptly and correctly, reflecting the product Casey agreed to,
  so that Casey can begin using the product without delay or confusion.
- To have balances that are accurate and explainable: every credit and debit traceable to
  a transaction Casey can recognise, with no unexplained movements.
- To have interest earned or charged calculated correctly, applied on the schedule Casey
  was told to expect, and visible in the account record in a form Casey can verify.
- To have payments initiated on Casey's behalf executed reliably, within the timeframes
  Casey was told to expect, and to receive clear confirmation or explanation when they
  do not complete.
- To have accounts closed cleanly when Casey asks, or when the product term ends, with
  no residual obligations or unexplained balances remaining.
- To be protected from financial harm when something goes wrong: to have errors corrected
  without Casey having to repeatedly explain the same situation, and to have the correction
  reflected accurately in the account record.
- To have personal and financial data held accurately, not retained beyond its purpose,
  and not accessible to parties who have no legitimate reason to see it.

## Constraints

- Casey is protected by consumer regulation: the FCA's Consumer Duty requires that
  Nucleus's behaviour — and the behaviour of every value stream that uses it — produces
  good outcomes for retail customers. This is not a soft aspiration. It is a regulatory
  obligation that shapes what "correct" means throughout the system. A technically correct
  ledger entry that produces a bad customer outcome is not acceptable.
- Casey has limited tolerance for ambiguity in financial communications. An error message
  that makes sense to an engineer does not make sense to Casey. A balance figure that
  requires knowledge of accrual accounting to interpret is not useful to Casey. The
  system does not serve Casey directly, but every value stream that does serve Casey
  depends on Nucleus providing data that is clear enough to be communicated without
  distortion.
- Casey may be vulnerable: in financial difficulty, recently bereaved, subject to a
  financial abuse situation, or otherwise in circumstances that require the bank to act
  with additional care. Restrictions, flags, and exceptional account actions exist
  partly to protect or accommodate Casey in these circumstances. The system must support
  these use cases without treating them as edge cases to be handled informally.
- Casey has rights of access to their own data under UK GDPR. The account record Nucleus
  holds is personal data. The audit trail Nucleus maintains is personal data. The design
  of the system must not foreclose the ability to respond to a subject access request by
  making it technically impossible to extract a coherent account history for a specific
  customer.
- Casey does not know what Nucleus is. Casey knows their bank, their product, and their
  account. If something goes wrong in Nucleus, the impact on Casey is real and financial,
  but the explanation Casey receives will come from Sasha, Maya, Liam, or Eddie —
  translated into product and customer language. Nucleus's error model must be precise
  enough that this translation is possible without information loss.

## Integration Pattern

None. Casey has no integration with Nucleus.

Casey's interests are represented in Nucleus interactions by:
- **Sasha, Liam, or Maya** — for product lifecycle events and account servicing outcomes.
- **Eddie** — for customer-initiated exceptions, complaints, corrections, and account
  actions taken on Casey's behalf by an operator.
- **Parker** — for payment execution and settlement outcomes.

## Usage in Stories

Casey should be named as the persona in a user story when the value being delivered is
most clearly expressed in terms of customer outcome rather than operational capability.
This is typically appropriate when:

- The story concerns a customer-facing financial consequence: a balance being correct,
  an interest payment being applied, a payment completing.
- The story concerns a protection or remediation: a restriction being applied to prevent
  harm, a correcting entry being posted to repair an error, an account being closed
  following a bereavement.
- The story's value statement would be tautological or purely operational if written for
  the technical client persona: "so that the ledger entry is posted" becomes "so that
  Casey's balance correctly reflects the payment received."

Casey should not be named as the persona when the story is primarily about system
integration, data exchange, or operational configuration where the customer outcome is
indirect and the immediate value is to the technical client. In those cases, use Sasha,
Liam, Maya, Robin, Alex, or Eddie as appropriate, and reserve Casey for stories where
the human financial consequence is the point.

## Interests By Domain Area

**Account open/close:** High interest. Account opening is the moment Casey's product
relationship with the bank is established in the core system. Closure is the moment it
ends. Both must be correct, timely, and consistent with what Casey was told to expect.

**Balances:** High interest. The balance is the number Casey sees and relies on to make
financial decisions. It must be accurate, up to date, and explainable in terms of
transactions Casey can recognise.

**Ledger entries:** High interest, indirectly. Every ledger entry is a financial event
that affects Casey's position. Casey does not see ledger entries directly, but depends
on them being correct in order for balances, statements, and interest calculations to
be trustworthy.

**Account servicing (interest, maturity):** High interest. Interest applied correctly
and on time is a product promise to Casey. A fixed-term maturity handled incorrectly
may leave Casey's funds in limbo or on a rate Casey did not agree to.

**Payments:** High interest. A payment that does not arrive, arrives late, or is applied
to the wrong account has an immediate and concrete impact on Casey. Payment failure is
one of the highest-volume sources of customer complaints in retail banking.

**Restrictions and flags:** High interest when they apply to Casey's account. A
restriction may prevent Casey from accessing their funds. The reason, the duration, and
the resolution path must be handled with care. Casey may be the subject of the restriction
or may be a victim of circumstances that caused it to be applied.

**Parameter value hierarchy:** No direct interest. Casey does not know this exists.
Its correctness matters to Casey only insofar as incorrect configuration produces
incorrect product behaviour — wrong interest rate, wrong term, wrong payment schedule.
The configuration is invisible to Casey; its consequences are not.
