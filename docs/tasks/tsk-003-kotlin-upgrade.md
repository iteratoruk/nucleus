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

[Populated during execution — record every new detekt violation introduced by the
upgrade and its resolution, every forced dependency change, every K2 compiler
behavioural change encountered, and the detekt internal Kotlin version configuration
applied.]
