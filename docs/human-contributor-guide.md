# Human Contributor Guide

## Overview

Nucleus uses a structured, documentation-driven approach to AI-assisted development.
Claude Code is used throughout the development lifecycle — for architecture modelling,
requirements authoring, and TDD implementation — but it operates within a defined
framework of roles, constraints, and reference documents that you, as a human
contributor, own and maintain.

This guide explains that framework: what each file and directory is for, how to use
Claude Code in each of the three contribution roles, and the prompt templates to start
each type of session.

Claude Code does not drive the project. You do. Claude Code is a thinking pair and a
capable implementor, operating under constraints you set. The documents in this
repository are the mechanism by which you set them.

---

## File and Directory Structure

### `/CLAUDE.md`

The project-level memory file for Claude Code. Loaded automatically at the start of
every Claude Code session in this repository.

Contains: the technical orientation of the project — stack, build commands, test
commands, architectural conventions, and codebase-specific rules that Claude must
follow in every session regardless of role.

**What belongs here:** things that are true of the codebase in every session without
exception. Build commands. Test runner invocations. Conventions about base classes,
error handling, Kafka listener annotations, audit event patterns. The kind of
information a new senior engineer would need to orient themselves on day one and would
need to keep in mind throughout.

**What does not belong here:** role-specific instructions, domain knowledge, persona
definitions, story content. Those belong in the `docs/` structure and are loaded
on demand.

Keep it under 200 lines. Beyond that, late instructions are increasingly likely to be
ignored under context pressure.

---

### `/.claude/CLAUDE.md`

The global memory file for Claude Code, applied across all projects on this machine.
Not committed to the repository. Loaded before the project-level `/CLAUDE.md`.

Contains: the engineering identity of the contributor — methodology (XP, TDD, BDD,
Lean/Kanban), design philosophy, communication preferences, and behavioural constraints
that apply regardless of which project is open.

**What belongs here:** your invariant engineering practices. TDD cycle discipline.
Gherkin as the language of acceptance criteria. Domain modelling before implementation.
How you want Claude to communicate disagreement, surface options, and handle uncertainty.

**What does not belong here:** project-specific conventions. Those live in `/CLAUDE.md`.

This file is personal. Each contributor maintains their own. It reflects how *you*
work, not how the project works.

---

### `/docs/architecture/`

Architecture documents produced in architect-role sessions with Claude Code. Each
document covers one bounded context or significant domain area.

Each document follows the domain model template defined in `docs/roles/architect.md`:
bounded context statement, ubiquitous language, aggregates with invariants and domain
events, context relationships, and open questions.

**Current documents:**
- `parameter-value-hierarchy.md` — the classification code tree, parameter value
  storage and resolution, account node attachment.
- `account-features.md` — the strongly-typed external feature catalogue, internal
  parameter key mapping, and the two initial features (asset interest, liability
  interest).

Architecture documents are living documents. They are updated as open questions are
resolved and as new domain areas are understood. They are the authoritative reference
for TDD sessions and story authoring sessions.

---

### `/docs/architecture/adrs/`

Architectural Decision Records. Each ADR captures a decision that forecloses other
reasonable options, encodes a non-obvious domain assumption, or has implications across
more than one bounded context.

ADRs are numbered sequentially (`ADR-001`, `ADR-002`, ...) and follow the template
defined in `docs/roles/architect.md`: context, decision, consequences (positive,
negative, risks), and alternatives considered.

**Status values:** `Proposed`, `Accepted`, `Superseded by ADR-NNN`.

ADRs are not deleted when superseded. The history of decisions — including decisions
later reversed — is part of the architectural record.

ADR candidates are identified during architecture sessions and named in the session
output. The ADRs themselves are written as a subsequent step, either in the same
session or in a dedicated follow-up.

---

### `/docs/personas/`

Persona definitions for all stakeholders and clients of Nucleus. Each persona is a
named character that encapsulates the goals, constraints, and integration pattern of
a stakeholder role.

Each persona file follows the format defined at the top of the persona files themselves:
role, type, responsibilities, goals, constraints, integration pattern, and interests
by domain area.

**Current personas:**

| File | Persona | Type |
|---|---|---|
| `cameron-the-configurer.md` | Cameron | Abstract configurer archetype |
| `sasha-from-savings.md` | Sasha | Active client — Savings |
| `liam-from-lending.md` | Liam | Active client — Lending |
| `maya-from-mortgages.md` | Maya | Active client — Mortgages |
| `robin-from-reporting.md` | Robin | Passive observer |
| `alex-from-accounting.md` | Alex | Passive observer |
| `eddie-from-enterprise.md` | Eddie | Human operator proxy |
| `parker-from-payments.md` | Parker | External counterparty |
| `casey-the-customer.md` | Casey | Indirect beneficiary |
| `ripley-from-risk.md` | Ripley | Internal authority |
| `otto-from-operations.md` | Otto | Platform operator |

Load the relevant personas at the start of architecture and story authoring sessions.
Do not load all personas into every session — load only those whose interests are
relevant to the domain area under discussion. Context is a limited resource.

**Cameron** is the abstract archetype for Sasha, Liam, and Maya. Load Cameron for
stories and architecture that concern the general configurer mechanism. Load the
specific persona when product-domain detail is material.

**Casey** is used as the story persona when Nucleus is the direct delivery mechanism
of a product promise to a customer — servicing stories where Nucleus, not the
configurer, is responsible for the outcome. For integration and configuration stories,
use the relevant technical client persona.

---

### `/docs/roles/`

Role instruction files for Claude Code. Each file defines a specific mode of work:
the persona Claude should occupy, the inputs it expects, the outputs it must produce,
and the constraints that govern the session.

Load a role file at the start of every session using the `@` reference syntax.

**Current roles:**

`architect.md` — domain modelling and architectural decision sessions. Output is
domain model documents and ADR candidates. No production code or tests.

`story-author.md` — requirements and user story authoring sessions. Output is
user stories in `docs/user-stories/` with persona, value statement, Gherkin
acceptance criteria, out-of-scope statement, and open questions. No production
code or tests.

`tdd-implementor.md` — TDD implementation sessions. Output is production code
and tests, written in strict red-green-refactor order. No story authoring or
architectural decisions.

**One role per session.** Do not mix roles in a single Claude Code session. An
architecture question that surfaces during a TDD session should be parked, noted,
and addressed in a subsequent architecture session — not resolved inline.

---

### `/docs/user-stories/`

User stories produced in story-author-role sessions. Each story is a separate
markdown file named by story identifier and short title:
`nuc-NNN-short-title.md`.

Each story follows the format defined in `docs/roles/story-author.md`: persona,
story statement (As / I want / So that), optional background, Gherkin scenarios,
out-of-scope statement, and open questions.

A story is not ready for a TDD session until all open questions are resolved. If
a story has open questions, resolve them — either directly or via an architecture
session — before opening a TDD session against it.

---

## Contributing with Claude Code

### The Three Roles

All Claude Code contribution falls into one of three roles. Each has a defined
scope, a role instruction file, and a prompt template below.

**Never mix roles in a single session.** If a TDD session surfaces an architectural
question, park it and address it in a subsequent architecture session. If a story
authoring session reveals a missing domain concept, park it and address it in an
architecture session before resuming story authoring. Context drift — where a session
accumulates mixed concerns — produces lower quality output across all of them.

**Use `/clear` between stories and between roles.** The context window is a limited
resource. A story that has been implemented does not need to remain in context while
the next story is being authored. Clear aggressively.

### Session Hygiene

Before opening any session:
- Confirm the relevant architecture documents are current. If they are not, run an
  architecture session first.
- For TDD sessions: confirm the story has no open questions.
- For story sessions: confirm the relevant personas and architecture documents are
  loaded.
- For architecture sessions: confirm what is settled (do not re-litigate) and what
  is open (do not assume).

At the end of any session that produces output:
- Ask Claude Code to save all outputs to the appropriate `docs/` location before
  clearing or closing the session.
- For TDD sessions: confirm which scenarios are now covered by passing tests and
  record this in the story file.

---

## Prompt Templates

These templates are starting points. Adjust the `@` references, story identifiers,
and domain area descriptions to match the session you are opening.

---

### Architecture Session

Use when: designing a new domain area, resolving open questions from an existing
architecture document, or producing ADRs for named candidates.

```
@docs/roles/architect.md
@docs/personas/[relevant-persona].md
@docs/architecture/[relevant-existing-doc].md   ← omit if this is a new area

---

We are opening an architecture session on [domain area].

## What we know

[List the settled facts that are not under discussion. Be explicit. Anything not
listed here may be treated as open.]

## What is not yet decided

[List the open questions, grouped by concern. For each, state why it matters — what
downstream decision depends on resolving it.]

## What to produce

Produce a domain model document following the template in `docs/roles/architect.md`,
targeted at `docs/architecture/[output-filename].md`.

Where a design decision warrants an ADR, identify it as a candidate. Continue ADR
numbering from [current next number].

Do not propose implementation detail: class names, database schemas, Kotlin types,
Spring annotations. The output of this session is a domain model and ADR candidates.
```

---

### Story Authoring Session

Use when: writing new user stories for a domain area, or refining existing stories
with updated acceptance criteria.

```
@docs/roles/story-author.md
@docs/personas/[acting-persona].md
@docs/personas/[additional-relevant-persona].md  ← load others whose interests
                                                    are relevant to this domain
@docs/architecture/[relevant-domain-doc].md

---

We are writing stories for [domain area / capability].

[State the capability or set of capabilities to be covered. Name the persona(s)
involved. If there is a natural starting point — e.g. the happy path of the
primary write operation — name it.]

Use [Cameron / Sasha / specific persona] as the persona for stories that concern
[general mechanism / specific product domain]. Use [other persona] only if a story
requires [specific detail] that [primary persona] cannot express.

Write stories in `docs/user-stories/` using the identifier sequence starting at
NUC-[NNN].
```

---

### TDD Implementation Session

Use when: implementing a specific user story that has no open questions and whose
domain model is current.

```
@docs/roles/tdd-implementor.md
@docs/user-stories/nuc-NNN-[story-title].md
@docs/architecture/[relevant-domain-doc].md
@docs/architecture/[additional-relevant-doc].md  ← e.g. account-features.md
                                                    if features are in scope

---

We are opening a TDD implementation session for **NUC-NNN: [Story title]**.

## Pre-implementation confirmation

Before proposing the first test, confirm:

1. The story has [N] scenario(s) with no open questions. ✓
2. Confirm that every concept in the story has a corresponding definition in the
   loaded architecture documents. Surface anything missing before writing a test.
3. The test entry point for this story is [AbstractApiTest / service layer / domain
   unit test]. [One sentence justifying the choice.]

## Domain context for this session

**The aggregate under test:** [Name the aggregate. State what the operation under
test must do to it in plain terms.]

**The invariant(s) being exercised:** [Name them from the architecture document.]

**The domain event(s):** [Name the events that must be raised. State that they are
verifiable via MockAuditService.]

**Explicit scope boundary:** [Name anything that is adjacent and tempting but
explicitly out of scope for this story, with the story number that covers it.]

## Constraints for this session

[List any constraints specific to this story beyond those in the role file:
precondition setup, endpoints that do not yet exist and whose schema must be agreed
before the first test, out-of-scope validation stubs, datetime/timezone concerns,
or gaps in the read path that may affect verifiability of a Then clause.]

## Proceed

Propose the first failing test. State which Then clause it covers. Stop and wait
for confirmation before writing any production code.
```

---

## Common Failure Modes

**Architecture questions surfacing in TDD sessions.** Park them. Note the question
in a comment or a scratch file. Finish the current test cycle, then open an
architecture session. Do not resolve architecture questions inline during TDD —
the answers will not be documented and will become implicit assumptions.

**Stories with open questions entering TDD.** A story with an unresolved open
question is not ready for implementation. The open question will surface as an
implicit decision during the TDD session, made under the pressure of a red test,
and will not be recorded anywhere. Resolve open questions in a story review or
architecture session first.

**Context accumulation across stories.** Use `/clear` between stories. A TDD session
that has implemented three stories carries context about the first two that is no
longer relevant and is consuming context window budget. Clear and reload only what
the current story needs.

**Role drift.** A session that starts as story authoring and drifts into architecture,
or a TDD session that starts debating acceptance criteria, produces lower quality
output in both modes. When you notice role drift, stop the current session, save
whatever output is worth keeping, clear, and reopen in the appropriate role.

**Loading too much context.** Load only the personas and architecture documents
relevant to the current session. Loading the full persona cast, all architecture
documents, and all user stories into a single session produces worse results than
loading the three or four documents that are genuinely needed. The `@` reference
system exists to make selective loading easy — use it selectively.
