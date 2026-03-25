# TSK-002: Upgrade Gradle wrapper from 8.14 to 9.4.1

## Goal

Upgrade the Gradle wrapper to version 9.4.1, the current latest stable release, and
resolve any breaking changes or deprecation warnings introduced by the upgrade. The
build must produce identical outputs and the full test suite must pass after the
upgrade.

## Motivation

Gradle 8.x is now in maintenance-only support following the release of Gradle 9.
Gradle 9.4.1 is the current stable release and includes Configuration Cache
improvements, Kotlin 2.2 language support, and security improvements to the Gradle
wrapper. Staying current reduces exposure to unpatched build tooling vulnerabilities
and avoids accumulating migration debt.

## Scope boundary

- The Gradle wrapper version and any directly required build script changes.
- Resolution of compilation errors, deprecation warnings, and behavioural changes
  introduced by the Gradle 8 → 9 upgrade.
- Does not include upgrading other dependencies (Spring Boot, Kotlin, detekt, spotless,
  etc.) unless they are directly required to compile or pass tests under Gradle 9.
  Any such forced upgrades must be noted as Findings and assessed separately.
- Does not include enabling Configuration Cache if it is not already enabled. If the
  build runs cleanly under Gradle 9 without Configuration Cache, that is the correct
  outcome for this task.
- Does not include migrating build scripts from Groovy DSL to Kotlin DSL or any other
  structural refactoring of the build files.

## Known breaking changes to investigate

Gradle 9 is a major version with documented breaking changes. Before making any
changes, read the Gradle 8 → 9 upgrade guide at
`https://docs.gradle.org/current/userguide/upgrading_version_8.html` and identify
which breaking changes apply to this build. Pay particular attention to:

- **Kotlin DSL and Kotlin language version.** Gradle 9 embeds Kotlin 2.2 and uses
  Kotlin language version 2.2, a shift from Gradle 8's Kotlin 2.0 runtime with
  language version 1.8. Build scripts and plugins written in Kotlin DSL may require
  syntax changes.
- **Configuration Cache is now the preferred mode.** Gradle 9 makes Configuration
  Cache the default. If the build does not currently support Configuration Cache,
  tasks or plugins may fail or emit warnings. Note any Configuration Cache
  incompatibilities as Findings but do not fix them unless they prevent the build
  from completing.
- **Deprecated API removals.** Gradle 9 removes APIs deprecated in Gradle 8. Check
  `build.gradle.kts` and any custom tasks or plugins for removed API usage.
- **Java 17 minimum requirement.** Gradle 9 requires Java 17 to run. Confirm the
  local and CI JVM version satisfies this requirement before upgrading.
- **Kotlin 2 K2 compiler.** The K2 compiler is the default in Kotlin 2.0+. If the
  project's Kotlin version predates K2 adoption, confirm compatibility.

## Upgrade procedure

1. Run `./gradlew wrapper --gradle-version=9.4.1 && ./gradlew wrapper` to update the
   wrapper. Do not manually edit `gradle/wrapper/gradle-wrapper.properties`.
2. Run `./gradlew build` and address any failures or deprecation warnings in order of
   severity: compilation errors first, then test failures, then warnings.
3. Run `./gradlew test`, `./gradlew detekt`, and `./gradlew spotlessCheck` and confirm
   all pass.

## Verification

- `./gradlew --version` reports `9.4.1`.
- `./gradlew build` completes without errors.
- `./gradlew test` passes with no regressions.
- `./gradlew detekt` passes.
- `./gradlew spotlessCheck` passes.
- No new warnings are introduced in the build output that indicate further required
  action (deprecation notices that signal a future breaking change are acceptable if
  they do not affect the current build).

## Findings

### Pre-task baseline

- Java version: OpenJDK 21 (Eclipse Adoptium Temurin). Satisfies Gradle 9's Java 17 minimum.
- Kotlin version: 2.0.21 declared in `build.gradle.kts`. K2 compiler already active.
- Gradle version before upgrade: 8.14 (from `gradle/wrapper/gradle-wrapper.properties`).
- Baseline green: `./gradlew test` (81 tests), `./gradlew detekt`, and `./gradlew spotlessCheck` all passed before any changes were made.

### Upgrade execution

No build script changes were required. The wrapper was updated by running:

```
./gradlew wrapper --gradle-version=9.4.1
./gradlew wrapper
```

`./gradlew build` passed on the first attempt under Gradle 9.4.1 with no compilation
errors, no test failures, and no changes to any source or build file beyond the wrapper
properties update.

### Gradle 9 Kotlin DSL runtime

`./gradlew --version` reports Gradle 9.4.1 embeds Kotlin 2.3.0 (not 2.2 as documented
in the upgrade guide — the guide appears to reflect the initial 9.0 release). The build
scripts compiled and executed without any syntax errors or K2-related failures.

### Deprecation warnings (Gradle 10 candidates)

Three deprecation warnings were emitted by plugins under `--warning-mode all`. None
originate from the project's own build scripts. All are scheduled for removal in
Gradle 10, not Gradle 9:

1. **`ReportingExtension.file(String)` deprecated** — origin: JaCoCo plugin or a
   plugin that wraps it. Replacement: `getBaseDirectory().file(String)`. Logged in
   Gradle's problems report. Does not affect the build.

2. **Legacy `Usage` attribute value `java-api-jars` deprecated** — origin: a
   dependency management or resolution plugin (most likely `io.spring.dependency-management`
   or the Kotlin Gradle plugin). Replacement: declare `java-api` + `LibraryElements=jar`.
   Scheduled to become an error in Gradle 10.

3. **Legacy `Usage` attribute value `java-runtime-jars` deprecated** — same origin as
   above. Replacement: `java-runtime` + `LibraryElements=jar`.

These three warnings require plugin updates (not changes to this project's build
scripts) before a Gradle 10 upgrade. They should be tracked as a prerequisite for
TSK-003 (or equivalent) when Gradle 10 becomes the target.

### Dependency upgrades forced by upgrade

None. No dependency versions were changed to satisfy Gradle 9 compatibility.

### Configuration Cache

Configuration Cache is not enabled in this project and was not enabled as part of
this task. Gradle 9 makes it the default for `gradle init` but does not enforce it
on existing builds. Each build run emitted a suggestion:

```
Consider enabling configuration cache to speed up this build
```

This is advisory only and does not affect correctness. Enabling Configuration Cache
is out of scope for this task.

### Final verification

| Check | Result |
|---|---|
| `./gradlew --version` reports 9.4.1 | ✅ |
| `./gradlew build` completes without errors | ✅ |
| `./gradlew test` passes (81 tests, no regressions) | ✅ |
| `./gradlew detekt` passes | ✅ |
| `./gradlew spotlessCheck` passes | ✅ |
| No new warnings requiring action in current build | ✅ (three Gradle 10 candidates from plugins only) |
