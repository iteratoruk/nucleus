# TSK-003: Upgrade Kotlin from 2.0.21 to 2.3.20

## Goal

Upgrade the project's Kotlin version from `2.0.21` to `2.3.20`, the current latest
stable tooling release, and upgrade the detekt plugin from `1.23.8` to
`2.0.0-alpha.2` — the minimum version with Kotlin 2.3.x support — configuring detekt
to use its own fixed Kotlin 2.3.0 compiler version independently of the project's
Kotlin version. The build must produce identical outputs and the full test suite must
pass after the upgrade.

## Motivation

Kotlin 2.0.21 is two minor language releases behind the current stable (2.3.20).
Kotlin 2.3.x brings K2 compiler improvements, Gradle 9 compatibility enhancements,
and the Build Tools API as the default for JVM compilation. Staying current reduces
accumulation of migration debt and ensures compatibility with current Gradle and
Spring Boot versions.

## Known constraint: detekt Kotlin version pinning

Detekt compiles its Gradle plugin against a fixed Kotlin version and must be
explicitly configured to use that version's compiler internally, regardless of the
project's Kotlin version. This is a deliberate detekt design decision.

The required versions, per the detekt compatibility table
(https://detekt.dev/docs/introduction/compatibility):

| detekt version | Kotlin version (internal) |
|---|---|
| `2.0.0-alpha.2` | `2.3.0` |

The Kotlin version declared in the project (`2.3.20`) and the Kotlin version used
internally by detekt (`2.3.0`) will differ. This is expected and correct. The detekt
plugin must be configured to use `2.3.0` for its own analysis compiler, not `2.3.20`.
Confirm the correct configuration mechanism from the detekt `2.0.0-alpha.2`
documentation before making changes.

## Scope boundary

- Kotlin version in `build.gradle.kts` and any related version catalogue entries.
- Detekt plugin version and its internal Kotlin compiler version configuration.
- Resolution of any compilation errors, test failures, or linting failures introduced
  by the Kotlin version change.
- Does not include upgrading Spring Boot, Gradle, or any other dependency unless
  directly required to compile or pass tests under Kotlin 2.3.20. Any such forced
  upgrades must be noted as Findings.
- Does not include adopting new Kotlin 2.3.x language features or refactoring
  existing code to use them. The upgrade is a toolchain change, not a code change.

## Known changes to investigate

Before making any changes, review the Kotlin 2.1.x, 2.2.x, and 2.3.x release notes
for JVM-specific and K2 compiler changes that may affect this codebase. Pay particular
attention to:

- **K2 compiler behavioural changes.** The K2 compiler may surface new warnings or
  errors on code that compiled silently under the old compiler. Treat new errors as
  blockers; treat new warnings as Findings to be assessed but not necessarily fixed
  in this task.
- **Spring Boot annotation processor compatibility.** Spring Boot's Kotlin support
  depends on kapt or KSP. Confirm the annotation processing configuration is
  compatible with Kotlin 2.3.20.
- **Detekt rule changes between 1.23.8 and 2.0.0-alpha.2.** The detekt major version
  bump may introduce new rules, remove deprecated rules, or change rule defaults.
  New detekt violations introduced by the upgrade that did not exist before are
  Findings — assess whether to fix or suppress them, but do not silently suppress
  them without recording the decision.
- **`@TransactionalRetryingKafkaListener` and other composite annotations.** Kotlin
  2.x K2 compiler has stricter handling of annotation processing and composed
  annotations. Confirm these compile cleanly under 2.3.20.

## Upgrade procedure

1. Update the Kotlin version in `build.gradle.kts` (or version catalogue) to
   `2.3.20`.
2. Update the detekt plugin version to `2.0.0-alpha.2`.
3. Configure detekt to use Kotlin `2.3.0` internally. Consult the detekt
   `2.0.0-alpha.2` documentation for the correct configuration block — this is likely
   a `detekt { kotlinVersion = "2.3.0" }` block or equivalent, but confirm from the
   documentation rather than assuming.
4. Run `./gradlew build` and address failures in order: build script compilation
   errors first, then production compilation, then test failures, then detekt
   violations.
5. After each change, record what broke and what fix was applied in Findings before
   moving to the next failure.
6. Once `./gradlew build` passes, run `./gradlew test`, `./gradlew detekt`, and
   `./gradlew spotlessCheck` individually and confirm each passes.

## Verification

- `./gradlew kotlinVersion` (or equivalent) confirms `2.3.20` is in use.
- `./gradlew detektMain --info` output confirms detekt is using Kotlin `2.3.0`
  internally.
- `./gradlew build` completes without errors.
- `./gradlew test` passes with no regressions.
- `./gradlew detekt` passes.
- `./gradlew spotlessCheck` passes.

## Findings

### F-001 — detekt 2.0.0-alpha.2 is not published to any accessible public Maven repository (BLOCKER)

**Status: Blocker. Task cannot be completed as specified until resolved.**

The task document specifies upgrading detekt to `2.0.0-alpha.2`, the minimum version
with Kotlin 2.3.x support. This version was released as a GitHub Release on 2026-01-27
(https://github.com/detekt/detekt/releases/tag/v2.0.0-alpha.2) but has not been
published to Maven Central, the Gradle Plugin Portal, or any other publicly accessible
Maven repository. All `2.0.0-alpha.x` versions (`alpha.0`, `alpha.1`, `alpha.2`) are
absent from both repositories.

Investigation steps performed:
- Maven Central (`repo1.maven.org`): 404 for all `2.0.0-alpha.x` coordinates. Latest
  available version is `1.23.8`.
- Gradle Plugin Portal (`plugins.gradle.org`): shows `1.23.8` as latest. No `2.x`
  versions listed.
- Sonatype OSSRH staging: not accessible externally. The detekt build configuration
  targets `ossrh-staging-api.central.sonatype.com` via nexus-publish, but the
  artifacts have not been promoted to Central.
- GitHub Packages: 401 (authentication required). Even if accessible, configuring
  GitHub Packages as a plugin repository requires a personal access token, which is
  not appropriate for a project build.

**Consequence:** When the Kotlin version is set to `2.3.20` with detekt `1.23.8` in
place, the `:detekt` task fails with:

```
detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.20.
This is not supported.
```

This is an enforced version check inside detekt — it is not possible to work around
it by configuration. The Kotlin upgrade and the detekt upgrade are inseparable.

**Candidate resolution:** The task must remain parked until detekt `2.0.0-alpha.2` (or
a later `2.0.0` release) is published to Maven Central or the Gradle Plugin Portal. At
that point the task can proceed unmodified. Monitor https://repo1.maven.org/maven2/io/gitlab/arturbosch/detekt/detekt-gradle-plugin/ for the appearance of a `2.x` version.

**Current build.gradle.kts state:** Restored to original versions (`kotlin 2.0.21`,
`detekt 1.23.8`). No net change has been made to the build file.

---

### F-002 — No configurable `kotlinVersion` property in detekt 2.0.0-alpha.2

**Status: Informational. Corrects an assumption in the task document.**

The task document states that detekt must be "configured to use Kotlin `2.3.0`
internally" and that the configuration is "likely a `detekt { kotlinVersion = \"2.3.0\" }` block or equivalent, but confirm from the documentation."

The actual detekt `2.0.0-alpha.2` release notes and the `detekt {}` DSL documentation
confirm that **no such configuration property exists**. The Kotlin compiler version
used by detekt is fixed to the version it was compiled against (Kotlin `2.3.0` for
`2.0.0-alpha.2`) and is not user-configurable. The compatibility table at
https://detekt.dev/docs/introduction/compatibility expresses this relationship as a
version-pairing constraint, not a configuration option.

When F-001 is resolved and the detekt upgrade proceeds, no detekt configuration block
for a Kotlin version is required or possible.

---

### F-003 — Kotlin 2.2.0 changes JVM default method generation for interfaces

**Status: Informational. To be assessed during the actual upgrade execution.**

Kotlin 2.2.0 changes the default JVM compilation mode for interfaces: interface
functions now compile to JVM default methods by default (with compatibility bridges),
replacing the previous `DefaultImpls` class-based approach. This is a binary
compatibility change. For this codebase the primary risk is in Spring proxy behaviour
for Kotlin interfaces. This requires explicit testing after the upgrade runs. No
action needed until F-001 is resolved.

---

### F-004 — K2 kapt became the default in Kotlin 2.2.20

**Status: Informational. Lower risk for this project.**

In Kotlin 2.2.20, K2 kapt became the default annotation processor. However, this
project does not use kapt — it uses the standard Java `annotationProcessor`
configuration (solely for `spring-boot-configuration-processor`). This change has no
direct effect on this build. Noted for completeness.

---

### F-005 — detekt 2.0.0-alpha.2 introduces breaking rule renames

**Status: Informational. To be assessed during the actual upgrade execution.**

The `2.0.0-alpha.2` changelog records several breaking rule-set renames:
- `documentation` → `comments`
- `empty` → `emptyblocks`
- `bugs` → `potentialbugs`
- `UnnecessaryAnnotationUseSiteTarget` rule removed
- `CommentOverPrivateMethod` → `DocumentationOverPrivateMethod`
- `ForbiddenImport` configuration options changed

If the project has a `detekt.yml` configuration file that references any of these
rule-set or rule names, those references will need updating. This will be addressed
when F-001 is resolved and the upgrade proceeds.
