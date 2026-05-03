# ADR-029: Account opening configuration completeness

**Date:** 2026-05-03
**Status:** Accepted

## Context

When a new account is opened, the resolved feature configuration for that account
must be acceptable for opening. The Account Feature Catalogue domain model
already establishes what makes a value valid: type correctness, applicability to
the account's ledger side, openness compliance, and conformance to any
property-specific value constraints (e.g. non-negative for an interest rate).
What it does not establish is what makes the *set* of values complete.

The question of completeness is concrete. A liability account with
`liabilityInterest.enabled` resolved to true but no value resolved for
`liabilityInterest.interestRate` is structurally valid (each property's value
considered alone passes its own checks), but is incomplete in the sense that
matters at servicing time: when the first interest accrual runs, no rate is
available, and the consuming context must either fail the accrual or fall back
to a default that may not be what the configurer intended. Either outcome is a
silent incorrectness whose root cause is a configuration bug discoverable at
opening time but not currently caught there.

OQ-1 of the Account domain model resolved that completeness is a facet of
validity, not a separate concern, and that the Account Feature Catalogue is the
authoritative source for both. The remaining work is to extend the catalogue
model and the account-features API validation sequence to express and enforce
completeness — specifically, conditional inter-property requirements ("if X
resolves to Y, Z must resolve to a value") and per-property default values
("absence of an explicit value here means this default is used") — and to
evaluate them as part of the validation pass at opening.

## Decision

Completeness of an account's resolved feature configuration at opening is
determined by the Account Feature Catalogue. The Account context computes the
resolved map for an account-to-be — account-level overrides over
classification-node-resolved values, with catalogue-declared defaults applied
where present — at the opening timestamp, and submits the resolved map to the
catalogue's validation logic. The catalogue rejects the map with a structured
per-property error if any required value is absent (subject to conditional
requirements), if any value is malformed (type), if any value is inapplicable
to the ledger side, if any value violates the property's openness category, or
if any conditional inter-property requirement is unsatisfied. The resolved map
is acceptable for opening if and only if no property fails any of these checks.

Two extensions to the Account Feature Catalogue domain model are required to
make the above operative.

**Conditional inter-property requirements.** A feature property's catalogue
definition may express that the property must resolve to a value (i.e. is
required at opening) under specified conditions on the resolved values of other
properties. The simple unconditional case ("this property is always required
at opening") is a degenerate form of the same mechanism. The illustrative case
is `liabilityInterest.interestRate` being required at opening when
`liabilityInterest.enabled` resolves to true; an account on which interest is
enabled must have a rate at which to accrue it.

**Per-property default values.** A feature property's catalogue definition may
declare a default value that the resolution function applies when no explicit
value resolves through the parameter value hierarchy walk. A property with a
default never resolves to "no value"; either an explicit value or the default
is returned. Declaring a default is the catalogue's mechanism for stating that
absence has a defined meaning at this property and is not itself a defect. A
property without a default that is also not declared required is "absent
permitted," and the consuming context decides what absence means.

The detailed form of these extensions — the catalogue schema additions, the
syntax for expressing conditional requirements, the interaction between
defaults and the explicit absence marker (ADR-006), the resolution-function
contract changes — belongs to the Account Feature Catalogue domain model, not
to this ADR. This ADR commits to the requirement that the extensions exist and
to the principle they enforce; their specific shape is for the Account Feature
Catalogue domain model document to specify when it is updated.

The account-features API validation sequence is extended correspondingly:
validation evaluates conditional requirements and applies default values
during the per-property validation pass, exhaustively (every violation across
every feature and every property is collected and returned per ADR-020), with
total submission rejection on any violation.

## Consequences

**Positive:** The catalogue is the single source of truth for both validity
and completeness, simplifying the mental model and the validation surface.
Errors that would otherwise surface at servicing time — a missing interest
rate causing silently incorrect accruals, a missing fixed term causing
maturity miscalculation — are caught at the moment they are most actionable:
opening time, when the configurer is in a position to correct the
configuration before any account starts accruing positions under a flawed
setup. Default-bearing properties allow the catalogue to express "absence is
defined" precisely, distinguishing it from "absence is a defect" — a
distinction the current model conflates.

**Negative:** The Account Feature Catalogue domain model and the
account-features API validation sequence both require extension. Until those
extensions are in place, the completeness check at opening is trivially
satisfied (no properties are declared required) and does not protect against
missing values; the gap between this ADR's commitment and an implementation
that delivers on it is bridged only when those extensions are made. The
extensions add complexity to the catalogue's property definition contract,
particularly around how conditional requirements are expressed and evaluated.

**Risks:** Catalogue authors may declare conditional requirements imprecisely,
producing false rejections (an opening rejected for missing a value the
configurer reasonably expected to inherit) or false acceptances (an opening
permitted with a configuration that turns out to be incomplete in a case the
conditional did not anticipate). The risk is mitigated by reviewing the
catalogue's conditional requirements alongside the property definitions they
relate to, and by making the catalogue extension itself a deliberately
reviewed change rather than a routine edit. A second risk is that defaults
are declared too liberally, masking configuration mistakes that should be
surfaced as errors; this is mitigated by treating the addition of a default
as a deliberate choice that requires justification, not a fall-back when no
clear policy exists.

## Alternatives Considered

Consume-time discovery of incompleteness was considered and rejected per
OQ-1. Deferring the error to servicing time would make the configurer's
mistakes harder to diagnose and to correct, and would risk silent
incorrectness at accrual or maturity time when the missing value is needed.

A separate completeness validator outside the catalogue was considered. It
was rejected: the catalogue is the authoritative source for what makes a
feature configuration valid, and splitting completeness into a separate
validator would create two places where rules about feature properties live,
with the drift potential that implies. The catalogue must own both axes
together.

A simpler model in which each property is either always required or always
optional (no conditional requirements) was considered. It was rejected: the
required-when-X-is-Y pattern is a genuine domain need (interest rates needed
when interest is enabled, term periods needed when fixed-term is enabled,
and prospectively others as the catalogue grows), and forcing it into the
unconditional model would either over-require (making interest rate
mandatory on all accounts including those that don't accrue interest) or
under-require (leaving the gap that motivates this ADR).