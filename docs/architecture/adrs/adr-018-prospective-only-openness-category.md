# ADR-018: PROSPECTIVE_ONLY Openness Category

**Date:** 2026-03-22
**Status:** Accepted

## Context

ADR-017 establishes three distinct openness categories for feature properties: `GLOBAL`
(no constraint), named processing boundary categories (`BUSINESS_DAY_CLOSE` and
analogues), and `PROSPECTIVE_ONLY`. ADR-017 defines the first two in full. This
decision defines `PROSPECTIVE_ONLY`.

Some feature properties, if set with a past effective datetime, would retroactively
corrupt the truth of derived internal properties that Nucleus has already calculated
and stored for accounts that are open and in service. The canonical example is fixed
term period: Nucleus calculates a maturity date from the fixed term period at account
opening and stores it immutably in the Account context. If a configurer could submit a
new fixed term period with an effective datetime in the past, the stored maturity date
of every account that opened against that node after the past effective datetime would
no longer correspond to the configuration that was actually in force at opening time.
This is a corruption of stored fact, not a configuration update.

Neither `GLOBAL` nor any named processing boundary category protects against this.
`GLOBAL` imposes no constraint at all. A named boundary such as `BUSINESS_DAY_CLOSE`
prevents backdating past the last accrual run, but a boundary can be open — no closed
dates recorded — while many accounts have already opened against the affected node and
had their maturity dates calculated. The constraint required here is independent of
boundary state: it is structural, arising from the property's relationship to derived
internal state. `PROSPECTIVE_ONLY` is that constraint.

## Decision

**`PROSPECTIVE_ONLY` is an openness category for feature properties whose past-effective
submission would corrupt derived internal properties of already-open accounts.** A
property classified `PROSPECTIVE_ONLY` carries the invariant that its effective datetime,
at the time of any write, must be strictly after the current wall-clock time. Past
effective datetimes are unconditionally rejected. Future effective datetimes are
unconditionally permitted. The constraint applies regardless of the closure state of any
processing boundary.

**The constraint is a datetime comparison, not a business date comparison.** The
submitted effective datetime is compared to the system clock at the moment of
validation. If the effective datetime is not strictly after the wall-clock time at that
moment, the submission is rejected with a structured error identifying the property,
the `PROSPECTIVE_ONLY` constraint, the submitted effective datetime, and the wall-clock
time at validation.

**`PROSPECTIVE_ONLY` does not prohibit future-dating.** A configurer may submit a
`PROSPECTIVE_ONLY` property with an effective datetime in the future. This is the
intended usage: a configurer pre-configures a new fixed term period to take effect at a
defined future point. Accounts that open before that point resolve the prior value and
have their derived internal properties calculated accordingly. Accounts that open after
that point resolve the new value and have their derived internal properties calculated
from it. The stored derived values of both groups are consistent with the configuration
in force at their respective opening times.

**`PROSPECTIVE_ONLY` does not require Parameter Configuration to know about account
openings.** The constraint is enforced at write time using only the submitted effective
datetime and the system clock. There is no dependency on the Account context, no query
of account opening datetimes, and no per-node closure state to maintain.

**Why the wall-clock constraint is sufficient.** At the moment a `PROSPECTIVE_ONLY`
property submission is accepted with effective datetime T_eff (which must be in the
future), no account has yet opened under the new value. Accounts that opened before
this submission used the configuration in force before T_eff and had their derived
internal properties calculated from it. Those stored values are unaffected by this
submission. When T_eff arrives, new accounts opening will resolve the new value and
calculate their derived internal properties from it. No stored derived value is
contradicted at any point.

The alternative formulation — "effective datetime must be after the latest account
opening datetime at any descendant node" — was considered and rejected. It requires
Parameter Configuration to maintain knowledge of per-account opening datetimes,
introducing a dependency on the Account context that violates the bounded context
model. It is also incorrect in the permissive direction: it would prevent a configurer
from submitting a `PROSPECTIVE_ONLY` property with a past effective datetime for a node
that has had no accounts opened against it, which is unnecessarily restrictive. The
wall-clock constraint is both sufficient and architecturally correct.

## Consequences

**Positive:**

- The constraint is self-contained and requires no external state. Validation is a
  single datetime comparison against the system clock.
- Configurers can pre-stage `PROSPECTIVE_ONLY` property changes ahead of their
  intended effective date. The category constrains past-effective writes, not
  future-effective writes.
- The rejection error is immediately actionable: it identifies the property, the
  constraint, and the submitted effective datetime, making the required correction
  apparent.

**Negative:**

- A configurer cannot use late registration for `PROSPECTIVE_ONLY` properties. If a
  configurer discovers that the wrong fixed term period was in force at some point in
  the past, they cannot correct it via the account-features API. The maturity dates of
  accounts opened under the incorrect configuration are immutable. Operational
  correction of individual accounts, where applicable, is Eddie's domain.
- The `PROSPECTIVE_ONLY` classification is a catalogue-definition-time decision.
  Reclassifying a property from `PROSPECTIVE_ONLY` to a boundary-governed category
  after the catalogue is in production use is a breaking change.

**Risks:**

- **Clock skew.** The wall-clock comparison uses the system clock at validation time.
  In a clustered deployment, clock skew between instances could cause a submission
  with an effective datetime very close to "now" to be accepted on one instance and
  rejected on another. This edge case does not affect the constraint's purpose and is
  not practically significant.
- **Misclassification.** A property that should be `PROSPECTIVE_ONLY` classified as
  `GLOBAL` or boundary-governed will not protect derived internal properties. The
  identification principle for `PROSPECTIVE_ONLY` (see ADR-019) is the guard against
  this.

## Alternatives Considered

**Prohibit all past effective datetimes for all properties.** All properties behave as
`PROSPECTIVE_ONLY`. Rejected: this eliminates the late registration capability for
boundary-governed properties, which is a documented and correct use case. A configurer
who was unable to submit a new interest rate on the intended date must be able to do so
retroactively within an open window.

**A per-node "earliest permitted effective datetime" updated as accounts open.**
Rejected: requires Parameter Configuration to track account openings, violating bounded
context boundaries. The wall-clock constraint at write time is sufficient.

**`PROSPECTIVE_ONLY` activates only after the first account opening.** Before any
account has opened against a node, past-effective writes are permitted for
`PROSPECTIVE_ONLY` properties. Rejected: requires account opening tracking, and the
constraint is intended to protect the first account opening as well as all subsequent
ones. A node with no accounts yet opened may have accounts opening imminently;
permitting past-effective configuration changes in that window is the scenario the
constraint is designed to prevent.