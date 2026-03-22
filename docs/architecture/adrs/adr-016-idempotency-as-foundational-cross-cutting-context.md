# ADR-016: Idempotency as a Foundational Cross-Cutting Context with Enforced Dependency Isolation

**Date:** 2026-03-22
**Status:** Accepted

## Context

Idempotency is a concern that cuts across multiple bounded contexts. The account
features context requires it; future contexts governing account lifecycle, payment
processing, and other state-modifying operations are likely to require it as well.

When a service is consumed by multiple bounded contexts, its placement in the
dependency graph matters. If the idempotency package were placed within or alongside
a specific bounded context — for example, within `accountfeatures` or `parameters` —
any other bounded context wishing to consume it would depend on that bounded context.
This introduces coupling between unrelated contexts and risks creating circular
dependency chains as the graph grows.

Alternatively, idempotency could be placed in a shared utilities layer without formal
bounded context status, governed by convention rather than enforced constraint. This
would permit consumption but would leave the dependency boundary implicit and
unenforced.

The existing architectural constraint framework — `BoundedContextDependencyTest` —
provides a mechanism for expressing and enforcing inter-context dependency rules as
tests. This mechanism was already in use for the `accountfeatures` and `parameters`
contexts.

## Decision

Idempotency is assigned to its own package, `iterator.nucleus.idempotency`, with the
status of a foundational cross-cutting context. It occupies the base of the bounded
context dependency graph: it depends on nothing within the Nucleus bounded context
graph, and all other bounded context packages may depend on it freely.

This constraint is enforced by `BoundedContextDependencyTest`. The test encodes the
rule that the `idempotency` package introduces no dependency on any other
`iterator.nucleus.*` bounded context package. A violation of this rule — introducing
such a dependency — fails the test.

Any future development of the idempotency context that requires a dependency on
another Nucleus bounded context is prohibited without an ADR explicitly revisiting
this decision and assessing the consequences for the dependency graph.

## Consequences

**Positive:**

Any bounded context may consume `IdempotencyService` without incurring a transitive
dependency on any other bounded context. The idempotency check is available to all
future contexts without architectural negotiation.

The dependency rule is machine-enforced. Violations are surfaced immediately as test
failures rather than discovered during review or after the fact.

The cross-cutting character of the context is explicit in the package structure and
documentation, rather than implicit in where the class happens to live.

**Negative:**

The idempotency context is permanently constrained in what it may depend on. If a
future requirement arises — for example, the need to raise a domain event from the
idempotency context, or to perform a domain-level validity check as part of recording
— it cannot satisfy that requirement by depending on a bounded context package without
revisiting this decision. The constraint may require the requirement to be re-expressed
through events, callbacks, or an extension point rather than a direct dependency.

**Risks:**

As the Nucleus bounded context graph grows, the foundational position of the
idempotency context may prove too narrow. If idempotency needs to evolve — for
example, to support operation-type-specific retention policies, distributed tracing
integration, or audit event emission — and those capabilities require knowledge of
domain concepts, the no-dependency constraint will require those capabilities to be
expressed through an abstraction rather than a direct coupling. This is the intended
design: the risk is that the abstraction adds complexity not justified by the benefit.
This should be assessed at the time any such requirement arises.

## Alternatives Considered

**Placement within the `parameters` package.** Rejected because it would impose a
dependency on the parameters package for any bounded context wishing to consume
idempotency. As the graph grows, this would create coupling between unrelated contexts
and risk circular dependencies.

**Shared utilities layer without formal bounded context status.** Rejected because an
unenforced convention is weaker than a machine-checked constraint. The existing
`BoundedContextDependencyTest` framework provides the means to enforce the boundary;
not using it would be inconsistent with the approach taken elsewhere in the codebase
and would leave the architectural intent implicit.

**No dedicated package — idempotency inlined per consuming context.** Rejected because
it would result in duplicate implementation of the same mechanism and inconsistent
idempotency semantics across contexts. A single, shared, tested implementation is
preferable for a concern this fundamental.