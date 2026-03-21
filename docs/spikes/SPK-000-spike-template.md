# SPK-NNN: [Short title — the question in abbreviated form]

**Status:** [Open | Complete | Abandoned]

---

## Question

[The aporia, stated precisely. This is the single question the spike exists to answer.
It must be specific enough that a person reading the completed spike can judge whether
the question has been answered. A question that cannot be answered definitively — only
refined — is not yet ready for a spike.]

## Motivation

[Which story, ADR, or design decision is blocked by this question. State the blocker
explicitly: "NUC-NNN cannot be written until this is resolved" or "ADR-NNN candidate
requires this question to be answered before the decision can be made." A spike with
no named blocker has not been properly motivated.]

## Time-Box

**Initial allocation:** [e.g. 2 hours / 1 session / half a day]

**Extension approval condition:** [The condition under which additional time may be
allocated. E.g. "if the question is partially answered but a specific sub-question
remains open, one additional session may be used to resolve it." Extension requires
explicit approval — a spike does not extend silently.]

**Time used:** [Populated at the end of each session.]

## Approach

[What will be investigated and how. Name the specific artefacts, documents,
experiments, or external references that will be consulted or produced. This is not
a plan to be followed rigidly — it is a prior statement of intent that makes
deviation visible.]

## Determined Output

[The form the answer will take. Choose one:

- **ADR:** An Architectural Decision Record will be produced in `docs/architecture/adrs/`,
  numbered [ADR-NNN]. The decision record is the output; the investigation is the
  input to it.
- **Recommendation document:** A structured recommendation saved to `docs/spikes/` as
  a companion to this document. State the decision the recommendation is intended to
  inform.
- **Demo notes:** A live demonstration with structured notes (see section below).
  Use this when the output is evidence rather than a decision — e.g. confirming that
  a dependency works as expected.

A spike that produces production code has become something else. The output type
declared here is the output type produced.]

## Demo Notes

[Complete this section only if the determined output is a demonstration.
Populate the headings below during the spike session.]

### Talking Points

[The key observations and findings to communicate during the demonstration.]

### Findings

[What was discovered. Include unexpected results, constraints encountered, and
anything that informs the decision or story the spike was motivating.]

### Demonstration Steps

[The steps of the live demonstration, in order. Include the commands, inputs, and
expected outputs so the demonstration can be reproduced.]

## Result

[Populated at the end of the spike. State the answer to the question posed in the
Question section. Then state the output produced: the ADR number written, the
recommendation document saved, or that demo notes are complete above.

If the spike was abandoned before answering the question, state why and what would
be needed to answer it. An abandoned spike with an honest account of where it stopped
is more useful than a forced conclusion.]