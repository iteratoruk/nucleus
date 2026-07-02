# Human Contributor Guide

## Overview

Nucleus uses a structured, documentation-driven approach to AI-assisted development.
Claude Code is used throughout the development lifecycle — for architecture modelling,
requirements authoring, and TDD implementation — but it operates within a defined
framework of roles, constraints, and reference documents that you, as a human
contributor, own and maintain.

This guide explains that framework: what each file, directory, and issue type is for,
how to use Claude Code in each of the five contribution roles, and the prompt templates
to start each type of session.

Claude Code does not drive the project. You do. Claude Code is a thinking pair and a
capable implementor, operating under constraints you set. The reference documents in
this repository and the issues in its tracker are the mechanism by which you set them.

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
definitions, story content. Those belong in the `docs/` structure or in the issue
tracker and are loaded on demand.

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
document covers one bounded context or significant domain area. These are files, not
issues — they are the durable, authoritative reference for the domain model.

Each document follows the domain model template defined in `docs/roles/architect.md`:
bounded context statement, ubiquitous language, aggregates with invariants and domain
events, context relationships, and open questions.

Architecture documents are living documents. They are updated as open questions are
resolved and as new domain areas are understood. They are the authoritative reference
for TDD sessions and story authoring sessions.

The domain layer was reset to a skeleton, so this directory is currently empty. Its
documents are re-grown one bounded context at a time as those contexts are re-opened
in architecture sessions.

---

### `/docs/architecture/adrs/`

Architectural Decision Records. Each ADR captures a decision that forecloses other
reasonable options, encodes a non-obvious domain assumption, or has implications across
more than one bounded context. ADRs are files, not issues.

ADRs are numbered sequentially (`ADR-001`, `ADR-002`, ...) and follow the template
defined in `docs/roles/architect.md`: context, decision, consequences (positive,
negative, risks), and alternatives considered.

**Status values:** `Proposed`, `Accepted`, `Superseded by ADR-NNN`.

ADRs are not deleted when superseded. The history of decisions — including decisions
later reversed — is part of the architectural record. (The prior ADR set was removed in
the reset; numbering resumes from `ADR-001` unless and until earlier records are
restored.)

ADR candidates are identified during architecture sessions and named in the session
output. The ADRs themselves are written as a subsequent step, either in the same
session or in a dedicated follow-up.

---

### `/docs/design/`

Technical design documents produced in technical-designer-role sessions with Claude Code.
Each document covers one cross-cutting concern (persistence, serialization, messaging, and
so on) and captures the implementation patterns realised in code as prescriptive guidance for
TDD sessions: given a recurring implementation problem, how Nucleus solves it, the canonical
example to follow, and the ways of getting it wrong.

These documents are the counterpart to the architecture documents in `docs/architecture/`. An
architecture document models what a thing *is* and is prior to any implementation; a technical
design document governs how that thing is faithfully inscribed in code and is *posterior* to
it — a pattern is captured only after it exists in the codebase. Where `docs/architecture/` is
implementation-agnostic, `docs/design/` names base classes, beans, annotations, configuration
keys, and migrations directly.

They follow the technical design document template defined in
`docs/roles/technical-designer.md`. Like architecture documents, they are living documents,
loaded on demand for the concern in play rather than held in every session. The directory is
currently empty; its documents are grown as patterns are harvested from the skeleton and from
subsequent TDD sessions.

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
| `alex-from-accounts.md` | Alex | Passive observer |
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
domain model documents in `docs/architecture/` and ADR candidates. No production code
or tests.

`technical-designer.md` — technical design sessions. Output is technical design documents
in `docs/design/` that capture implementation patterns realised in code as reusable guidance
for TDD sessions. No production code or tests. Where the architect models what a thing *is*,
prior to implementation, the technical designer documents how it is *built*, after the code
exists.

`story-author.md` — requirements and user story authoring sessions. Output is a GitHub
issue labelled `story` with persona, value statement, Gherkin acceptance criteria,
out-of-scope statement, and open questions. No production code or tests.

`tdd-implementor.md` — TDD implementation sessions. Output is production code
and tests, written in strict red-green-refactor order. No story authoring or
architectural decisions.

`task-implementor.md` — task implementation sessions. Output is behaviour-preserving
changes to the codebase, verified by the full test suite and static analysis. No new
functional behaviour, no new tests, no architectural decisions.

The architect role also handles spike sessions (session type 4 in the architect role
file). A spike is not a separate role — it is a distinct entry point and output type
within the architect role.

**One role per session.** Do not mix roles in a single Claude Code session. An
architecture question that surfaces during a TDD session should be parked, noted,
and addressed in a subsequent architecture session — not resolved inline.

---

### GitHub Issues — stories, tasks, and spikes

User stories, tasks, and spikes are tracked as GitHub issues, not as files in the
repository. Each type is distinguished by a label:

| Type | Label | What it is |
|---|---|---|
| User story | `story` | A requirement expressed as persona + value statement + Gherkin acceptance criteria. |
| Task | `task` | Behaviour-preserving engineering work — upgrades, restructuring, lint application. |
| Spike | `spike` | A time-boxed investigation into a question that blocks a story or decision. |

**The issue number is the identifier.** There is no separate `NUC-`/`SPK-`/`TSK-`
sequence — an item is referred to by its GitHub issue number (e.g. `#42`), and its type
is read from its label.

Issue bodies follow a fixed structure, scaffolded by the templates in
`.github/ISSUE_TEMPLATE/`:

- **Story** (`story.md`): persona, story statement (As / I want / So that), optional
  background, Gherkin scenarios, out-of-scope statement, open questions.
- **Spike** (`spike.md`): question, motivation, time-box, approach, determined output,
  and a result section populated at the end of the spike.
- **Task** (`task.md`): goal, motivation, scope boundary, verification steps, and a
  findings section populated during execution.

Create an issue with `gh issue create` (selecting the appropriate template) or the web
form. Load an issue into a Claude Code session with `gh issue view <number> --comments`.

The readiness rules are unchanged by the move to issues:

- A story is not ready for a TDD session until all open questions are resolved.
- A spike is not ready to open until its Question, Motivation, Time-Box, Approach, and
  Determined Output are filled in.
- A task is not ready to open until its Goal, Motivation, Scope Boundary, and
  Verification Steps are stated.

Outputs and findings are recorded back on the issue as comments or edits to the issue
body — not as files. The exception is a spike whose determined output is an architecture
document or ADR: that output is saved to `docs/` as a file, and the issue records that
it was produced and where.

---

## Contributing with Claude Code

### The Five Roles

All Claude Code contribution falls into one of five roles. Each has a defined
scope, a role instruction file, and a prompt template below.

**Never mix roles in a single session.** If a TDD session surfaces an architectural
question, park it and address it in a subsequent architecture session. If a story
authoring session reveals a missing domain concept, park it and address it in an
architecture session before resuming story authoring. If a TDD session produces a reusable
implementation pattern, note it and capture it in a subsequent technical design session
rather than writing the guidance inline. Context drift — where a session accumulates mixed
concerns — produces lower quality output across all of them.

**Use `/clear` between stories and between roles.** The context window is a limited
resource. A story that has been implemented does not need to remain in context while
the next story is being authored. Clear aggressively.

### Session Hygiene

Before opening any session:
- Confirm the relevant architecture documents are current. If they are not, run an
  architecture session first.
- For TDD sessions: confirm the story issue has no open questions.
- For story sessions: confirm the relevant personas and architecture documents are
  loaded.
- For architecture sessions: confirm what is settled (do not re-litigate) and what
  is open (do not assume).
- For technical design sessions: confirm the pattern already exists in code. If it does
  not, it is not ready to document — build it in a TDD session first.
- For spike sessions: confirm the spike issue has its Question, Motivation, Time-Box,
  Approach, and Determined Output fields complete before starting.
- For task sessions: confirm the baseline is green before making any changes.

At the end of any session that produces output:
- Architecture documents and ADRs: save them under `docs/` before clearing or closing
  the session.
- Technical design documents: save them under `docs/design/` before clearing or closing
  the session.
- Stories, tasks, and spikes: create or update the corresponding GitHub issue with `gh`
  — do not leave the output only in the session transcript.
- For TDD sessions: record which scenarios are now covered by passing tests as a comment
  on the story issue.
- For spike sessions: populate the Result section on the spike issue and save any
  determined output (document or ADR) to disk.
- For task sessions: confirm the full build passes and record any findings as a comment
  on the task issue before closing.

---

## Prompt Templates

These templates are starting points. Adjust the `@` references, issue numbers, and
domain area descriptions to match the session you are opening.

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

### Technical Design Session

Use when: capturing an implementation pattern realised in a TDD session, or backfilling the
patterns latent in existing code (the skeleton), as reusable guidance for future TDD sessions.

```
@docs/roles/technical-designer.md
@docs/architecture/[relevant-domain-doc].md   ← reference only, if the pattern serves a domain
                                                concept; omit if purely infrastructural

---

We are opening a technical design session on [concern].

## The pattern(s) to capture

[Point at the code that realises the pattern — the files, types, base classes, or package.
Name the problem it solves. If this is a harvest, name the story or architecture concept the
pattern served in the TDD session that produced it; if this is a backfill, name the
cross-cutting concern.]

## What is settled

[What about this pattern is not under discussion — that the realising code exists and is
correct, the concern boundary, any related documents already written.]

## What to produce

Produce a technical design document following the template in
`docs/roles/technical-designer.md`, targeted at `docs/design/[concern].md` — or add a pattern
to the existing document for this concern.

Capture only patterns that exist in code and will recur. Do not invent patterns for code that
does not yet exist — redirect those to a TDD session. Reference the relevant architecture
document for any domain invariant the pattern serves; do not restate the domain. Name ADR
candidates; do not write the ADRs here.
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

Create one GitHub issue per story, labelled `story`, following the story issue template
(`.github/ISSUE_TEMPLATE/story.md`). Give each a short imperative title and create it
with `gh issue create`.
```

---

### Task Authoring Session

Use when: writing new tasks or refining existing tasks.

```
@docs/roles/task-author.md

---

We are opening a task authoring session to produce a task.

## The work to be documented

[Describe the task in your own words: what needs to be done, what prompted it,
and what you already know about its scope. Include any findings, technical notes,
or research that is already available. Be as specific as you can — the more
context provided here, the more precisely the scope boundary can be drawn.]

## Research needed before scoping

[Optional. List anything that needs to be looked up before the scope boundary can
be written precisely: release notes for a version upgrade, compatibility tables,
changelog entries, documentation for a tool or library involved. If you have
already done this research, summarise the relevant findings here instead.

If no research is needed, delete this section.]

## Known constraints

[Optional. List anything that is already known to be in scope or out of scope,
any dependencies on prior tasks or stories, and any risks that should be
pre-populated in the Findings section.

If none, delete this section.]

---

Before writing the issue:
1. Check existing open task issues (`gh issue list --label task`) to avoid duplication.
2. Conduct any research listed above and report findings before drafting.
3. If the work does not warrant a formal task, say so and explain why.

Produce the completed task as a GitHub issue labelled `task`, following the task issue
template (`.github/ISSUE_TEMPLATE/task.md`). Create it with `gh issue create`.
```

### Spike Authoring Session

Use when: writing new spikes or refining existing spikes.

```
@docs/roles/spike-author.md

---

We are opening a spike authoring session to produce a spike.

## The question to be investigated

[State the aporia as precisely as you can: what is genuinely unknown, and why
is the team unable to proceed confidently without answering it? A good question
is specific enough that, when the spike concludes, it will be unambiguous whether
it has been answered.]

## What is blocked

[Which story, ADR, architectural decision, or task cannot proceed until this
question is answered? A spike without a dependency is a spike without urgency.]

## What is already known

[Summarise any prior investigation, constraints already established, or options
already ruled out. This prevents the spike from re-covering ground already
covered and helps bound the investigation.]

## Initial thoughts on approach

[Optional. If you already have a view on how the investigation should proceed —
what to try, what to compare, what documentation to read — describe it here.
The spike will refine this, but having a starting point helps scope the time-box.]

## Proposed time-box

[Your initial estimate of how long the investigation should take, and the
condition under which an extension would be appropriate.]

---

Before writing the issue:
1. Check existing open spike issues (`gh issue list --label spike`) to avoid duplication.
2. If the question as stated cannot be answered definitively by a time-boxed
   investigation — for example, because it is actually a domain modelling question
   or an open requirements question — say so before producing the issue.
3. If the work does not warrant a formal spike (the question can be answered in
   minutes by reading documentation), say so and explain why.

Produce the completed spike as a GitHub issue labelled `spike`, following the spike issue
template (`.github/ISSUE_TEMPLATE/spike.md`). Create it with `gh issue create`.
```

### TDD Implementation Session

Use when: implementing a specific user story that has no open questions and whose
domain model is current.

```
@docs/roles/tdd-implementor.md
@docs/architecture/[relevant-domain-doc].md
@docs/architecture/[additional-relevant-doc].md  ← e.g. account-features.md
                                                    if features are in scope

---

Load the story from its GitHub issue:

    gh issue view <number> --comments

We are opening a TDD implementation session for **#<number>: [Story title]**.

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
explicitly out of scope for this story, with the issue number that covers it.]

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

### Spike Session

Use when: a question cannot be answered confidently enough to write a story or make
an architectural decision, and that question is blocking identifiable work.

Before opening this session, create the spike issue (labelled `spike`) and fill in its
Question, Motivation, Time-Box, Approach, and Determined Output fields. The session
begins from the issue, not from an open-ended area.

```
@docs/roles/architect.md
@docs/architecture/[relevant-doc].md   ← if the spike concerns an existing domain area

---

Load the spike from its GitHub issue:

    gh issue view <number> --comments

We are opening a spike session for **#<number>: [Question in abbreviated form]**.

The issue's Question, Motivation, Time-Box, Approach, and Determined Output fields are
complete.

## What this spike must answer

[Restate the question from the issue in one sentence.]

## What is blocked

[Name the story, ADR, or decision that cannot proceed until this is answered.]

## Time-box

[Restate the initial allocation from the issue.]

## Proceed

Begin the investigation following the approach in the issue. Record findings in the
issue's Result section (as a comment or body edit) as they arise. Produce the determined
output declared in the issue; if it is an architecture document or ADR, save it under
`docs/` and link it from the issue. Surface the state explicitly at the time-box boundary.
```

---

### Task Session

Use when: a piece of engineering work must be done that has no functional impact on
the system's behaviour when performed correctly — dependency upgrades, package
restructuring, linting rule application, framework migrations.

Before opening this session, create the task issue (labelled `task`) with its Goal,
Motivation, Scope Boundary, and Verification Steps complete.

```
@docs/roles/task-implementor.md

---

Load the task from its GitHub issue:

    gh issue view <number> --comments

We are opening a task session for **#<number>: [Task title]**.

## Pre-task confirmation

Before making any changes, confirm:

1. The task issue is complete: Goal, Motivation, Scope Boundary, and Verification
   Steps are all stated.
2. The baseline is green: run `./gradlew test`, `./gradlew detekt`, and
   `./gradlew spotlessCheck` against the unmodified codebase and confirm all pass.

## Scope boundary for this session

[Restate the scope boundary from the task issue. Name anything adjacent that is
explicitly not part of this task.]

## Proceed

Execute the task within the stated scope. Record any findings — regressions,
candidate stories, candidate spikes — as a comment on the task issue. Do not absorb
findings into the implementation. Confirm the full build passes on completion.
```

---

## Identifiers and Commit Conventions

| Work unit | Identifier | Label | Commit prefix | Home |
|---|---|---|---|---|
| User story | GitHub issue `#N` | `story` | `feat:` / `fix:` | GitHub issue |
| Spike | GitHub issue `#N` | `spike` | `docs:` * | GitHub issue |
| Task | GitHub issue `#N` | `task` | `chore:` | GitHub issue |
| ADR | `ADR-NNN` | — | `docs:` | `docs/architecture/adrs/adr-NNN-title.md` |
| Architecture document | — | — | `docs:` | `docs/architecture/[name].md` |
| Technical design document | — | — | `docs:` | `docs/design/[name].md` |

\* A spike whose findings live only on the issue may produce no commit at all. A spike
whose determined output is an ADR or architecture document commits that file with `docs:`.

All commit messages follow [Conventional Commits](https://www.conventionalcommits.org/).
Reference the issue an item belongs to in the commit body or pull request description
with `Closes #N` / `Fixes #N` so GitHub closes it on merge. The issue number may also
appear as the commit scope, e.g. `feat(#42): ...`.

---

## Common Failure Modes

**Architecture questions surfacing in TDD sessions.** Park them. Note the question
as a comment on the issue or in a scratch note. Finish the current test cycle, then open
an architecture session. Do not resolve architecture questions inline during TDD —
the answers will not be documented and will become implicit assumptions.

**Stories with open questions entering TDD.** A story with an unresolved open
question is not ready for implementation. The open question will surface as an
implicit decision during the TDD session, made under the pressure of a red test,
and will not be recorded anywhere. Resolve open questions in a story review or
architecture session first, and edit the story issue before opening the TDD session.

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
documents, and every open issue into a single session produces worse results than
loading the three or four documents that are genuinely needed. The `@` reference
system and `gh issue view` exist to make selective loading easy — use them selectively.

**Spikes that produce code.** A spike session that begins producing production code
has drifted into implementation. Stop. Save whatever investigation output exists.
Open a story authoring session to capture the requirement, then a TDD session to
implement it. The spike is complete when its determined output is produced — not when
the code is written.

**Spikes without a determined output.** A spike whose Determined Output field says
"TBD" or "depends on what we find" is not ready to open. The output type must be
decided before the session begins. If it is genuinely unclear what form the answer
should take, that is a question for an architecture session, not a spike.

**Technical design ahead of the code.** A technical design session that documents a
pattern which does not yet exist in the codebase has inverted the flow: it is inventing
guidance, not capturing it, and invented patterns are speculative generality. Stop. If the
pattern is genuinely needed, build it in a TDD session against a story, then harvest it in a
subsequent technical design session.

**Tasks that introduce functional behaviour.** A task that changes what the system
does — rather than how it is structured — has become a story. If this is discovered
mid-task, stop, record the finding on the task issue, and open a story authoring
session. Do not ship functional behaviour under a `chore:` commit.

**Weakening tests to make a task pass.** If a task causes tests to fail and the
correct response is not to fix the task implementation, that is a finding — not a
reason to modify the tests. Record it and surface it before proceeding.