# ADR-019: Derived Internal Properties

**Date:** 2026-03-22
**Status:** Accepted

## Context

ADR-018 establishes that a feature property is classified `PROSPECTIVE_ONLY` when its
past-effective submission would corrupt derived internal properties of already-open
accounts. To apply this classification correctly — now and for future properties — the
concept of a derived internal property must be precisely defined, and a principle must
be stated that allows any given feature property to be evaluated against it without
relitigating the question case by case.

This decision defines the derived internal property concept, states the identification
principle, and records the maturity date as the first canonical instance.

## Decision

**A derived internal property is a value that Nucleus calculates from one or more
account feature property values at a defined lifecycle event and stores as an immutable
fact in the Account context.** The distinguishing characteristics are:

1. The value is calculated by Nucleus, not submitted by a configurer. Configurers
   influence it only indirectly, by configuring the feature properties from which it
   is derived.
2. The calculation occurs at a defined lifecycle event — typically account opening —
   using the feature property values resolved at that event. The calculation does not
   recur; it happens once per account per lifecycle event.
3. The result is stored immutably in the Account context. It is not a parameter value
   in the Parameter Configuration hierarchy. It is not subject to resolution or
   supersession. It is a fact about the account, fixed from the moment of derivation.
4. A retroactive change to a contributing feature property — setting an effective
   datetime that precedes the lifecycle event at which the derivation occurred — would
   make the stored derived value inconsistent with what would have been calculated had
   the new configuration been in force at the lifecycle event.

**The identification principle for `PROSPECTIVE_ONLY` classification:**

A feature property P must be classified `PROSPECTIVE_ONLY` if it is a contributing
input to any derived internal property. The test is: if a past effective datetime were
permitted for P, would any stored derived value — at any account that opened while P
was in force — become inconsistent with the configuration basis on which it was
calculated? If yes, P is `PROSPECTIVE_ONLY`.

This test is applied at catalogue definition time. It does not depend on whether any
accounts have yet opened; it is a structural property of the relationship between the
feature property and the derived internal property it contributes to.

A feature property that does not contribute to any derived internal property is not
`PROSPECTIVE_ONLY`. Classifying a property as `PROSPECTIVE_ONLY` without a derived
internal property rationale is an error of conservatism, not a safety measure — it
removes the late registration capability without a corresponding benefit.

**Derived internal properties belong to the Account context, not Parameter
Configuration.** They are calculated using configuration values but are not
configuration. They are facts about specific accounts, not about classification nodes.
They are managed under the Account context's immutability and consistency guarantees.
Parameter Configuration has no knowledge of what derived internal properties exist, has
no record of their values, and has no responsibility for their accuracy. Its
responsibility is limited to enforcing the `PROSPECTIVE_ONLY` constraint at write time
so that the Account context's derived internal property records cannot be retroactively
invalidated by a configuration change.

**The maturity date is the first identified derived internal property.** It is
calculated at account opening as `maturity_date = opening_datetime + fixed_term_period`,
where `fixed_term_period` is the value of the `fixedTerm.termPeriod` feature property
resolved from the parameter hierarchy at the moment of opening. The result is stored
immutably in the Account context against the account. Therefore, `fixedTerm.termPeriod`
is classified `PROSPECTIVE_ONLY`. Accounts already open when a new fixed term period
configuration becomes effective retain their opening-time maturity dates; the new
configuration applies only to accounts opened on or after the new effective datetime.

## Consequences

**Positive:**

- The identification principle gives future architecture and catalogue definition
  sessions a clear, repeatable test for `PROSPECTIVE_ONLY` classification. The maturity
  date case does not need to be relitigated for each new property that might resemble it.
- The separation of derived internal properties into the Account context is a clean
  boundary: Parameter Configuration enforces the write constraint; the Account context
  owns the stored value. Neither context is burdened with the other's concerns.
- The principle is conservative in the right direction: it requires a positive
  identification of a derived internal property to justify `PROSPECTIVE_ONLY`
  classification. Absent such identification, `GLOBAL` (or an appropriate named
  boundary) is correct.

**Negative:**

- Reclassifying a feature property from boundary-governed to `PROSPECTIVE_ONLY` after
  accounts have opened requires an architectural assessment of whether stored derived
  internal property values remain consistent. The reclassification cannot be treated as
  a pure configuration change; it may require Account context data to be reviewed.
- Adding a new derived internal property that depends on an existing boundary-governed
  feature property requires reclassifying that property as `PROSPECTIVE_ONLY` — a
  breaking catalogue change if the catalogue is in production use.

**Risks:**

- **Undiscovered derived internal properties.** If a future Account Servicing
  implementation calculates and stores a derived internal property from a feature
  property that was not classified `PROSPECTIVE_ONLY` at catalogue definition time, the
  constraint designed to protect that property will be absent. The identification
  principle must be applied at design time, before implementation; post-hoc discovery
  of an unprotected derived internal property is a design failure. Architecture sessions
  that introduce new account lifecycle processing must explicitly evaluate whether any
  new derived internal properties arise.
- **Indirect contributions.** A feature property may contribute to a derived internal
  property indirectly — through an intermediate calculation — rather than directly.
  The identification principle applies equally in both cases: if a retroactive change
  to P would corrupt any stored derived value through any chain of derivation, P is
  `PROSPECTIVE_ONLY`. The indirectness of the contribution does not weaken the
  requirement.

## Alternatives Considered

**Treat all feature properties as `PROSPECTIVE_ONLY`.** No backdating permitted for any
property. Rejected: this eliminates the late registration capability for
boundary-governed properties, which is a documented and correct use case. Late
registration and `PROSPECTIVE_ONLY` address different problems; conflating them produces
an overly restrictive model.

**Record derived internal properties in Parameter Configuration.** Parameter
Configuration maintains a registry of (feature property, derived internal property)
pairs and validates at write time by querying whether any derived internal property
would be affected. Rejected: this places Account context concerns inside Parameter
Configuration, crossing the bounded context boundary. The `PROSPECTIVE_ONLY`
classification at catalogue definition time is the appropriate mechanism — the
constraint is declared where the property is defined, not enforced by querying runtime
state.

**Allow correction of derived internal properties via the account-features API.**
A special mechanism permits backdating of `PROSPECTIVE_ONLY` properties with an
explicit acknowledgement that stored derived values are now inconsistent and must be
recalculated. Rejected: this places Account context recalculation logic under Parameter
Configuration's write path, and introduces a category of operation (retroactive
correction of immutable stored facts) that should go through Eddie's operational
tooling and an explicit account-level override, not through a bulk classification-node
configuration change.