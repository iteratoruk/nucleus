# TSK-NNN: [Short imperative title]

**Status:** [In Progress | Complete | Parked]

---

## Goal

[A precise statement of what this task achieves when complete. One or two sentences.
State the end state, not the steps to reach it.]

## Motivation

[Why this task is being done now. Name the driver: a dependency upgrade with a
security advisory, a framework version that enables a subsequent story, a linting
rule that has been agreed but not yet applied. If the motivation is a prerequisite
for a story or spike, name it.]

## Scope Boundary

[An explicit statement of what this task does not cover. Name adjacent work that is
in scope for other tasks, stories, or spikes — not this one. The scope boundary
exists to prevent absorption of findings during execution.]

## Verification Steps

All tasks must pass the full build before they are complete:

```bash
./gradlew test
./gradlew detekt
./gradlew spotlessCheck
```

[List any task-specific additional verification steps here. For example: confirming
a dependency version in `build.gradle.kts`, running a specific test class that is
most directly affected by the change, or verifying a generated artifact.]

## Findings

[Populated during execution. Each finding is a piece of work discovered during the
task that is not within scope: a regression, a candidate story, a question that
warrants a spike. Record each with enough detail to open a separate work item.

Format:

**Finding [N]:** [Description of the finding.]
**Type:** [Story | Spike | Task]
**Suggested identifier:** [NUC-NNN | SPK-NNN | TSK-NNN — assign provisionally]
**Detail:** [What would need to be in scope for the new item. One or two sentences.]

Leave this section empty until findings arise.]