# RFP-[NNN]: [Short imperative title]

**Date:** [YYYY-MM-DD]
**Status:** [Proposed | Accepted → TSK-NNN | Accepted → SPK-NNN | Deferred | Rejected]
**Bounded context:** [The context this proposal applies to]
**Produced by:** Code review session on [date or session reference]

---

## Finding

[What is the specific problem with the current implementation? Be concrete: name the
classes, methods, or patterns involved. Do not describe the problem in abstract terms
— describe what you can see in the code.]

## Benefit

[What is the concrete, observable benefit of addressing this? Choose from:
readability, maintainability, extensibility, performance, reduced duplication, better
domain expression, elimination of a library reinvention. State the benefit in terms
of what will be materially easier or safer after the change, not in terms of
principles satisfied.]

## Proposed Approach

[Describe the target structure. This may be prose, pseudocode, or a sketch of the
proposed API. It must not be working production code — that belongs in the task or
spike that follows. Be specific enough that an implementor can derive the TDD cycle
from this description without making structural decisions.]

## Scope

[What code is affected? Name the packages, classes, and test files involved. Be
explicit about what is not in scope — what adjacent code will not change.]

## Verification Criterion

[What existing test or tests will confirm that the refactoring has preserved
observable behaviour? Name the test class(es) and, where relevant, the specific
scenarios. The criterion must be independent of the internal names being changed.

If existing test coverage is insufficient to protect this refactoring, state this
explicitly and identify what tests must be added before the refactoring proceeds.
Those tests are a prerequisite, not part of the refactoring itself.]

## Risk

[What could go wrong? Consider: behaviour change that tests do not currently detect,
performance regression, increased complexity in the short term before the benefit
is realised, dependency on a library that may not be stable. State the mitigations
for each risk identified.]

## Work Unit Classification

[One of:
- **Task (TSK-NNN):** behaviour-preserving restructuring with clear verification and
  low risk. `chore:` commit prefix.
- **Spike (SPK-NNN):** genuine uncertainty about whether the proposed approach is
  better, or about whether a candidate library is suitable. Spike resolves the
  question; task follows if the answer is yes.
- **ADR candidate:** if this proposal encodes a decision that forecloses other
  reasonable options or has cross-context implications, name the ADR here.]

## Decision

[Populated when the proposal is reviewed. Accept, defer (with reason), or reject
(with reason). If accepted, record the resulting work unit identifier.]
