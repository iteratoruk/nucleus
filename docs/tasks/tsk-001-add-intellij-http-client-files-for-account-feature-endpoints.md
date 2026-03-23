# TSK-001: Add IntelliJ HTTP client file for AccountFeatureController endpoints

## Goal

Add a `.http` file to the repository that allows developers and operations staff to
interact with the two `AccountFeatureController` endpoints directly from IntelliJ,
without requiring a separate REST client. The file must work against `localhost:8080`
by default and be variableised so that it can be pointed at any running Nucleus
instance with minimal editing.

Requests should include realistic, meaningful example payloads drawn from the account
features domain — not placeholder values. The file should be immediately useful for
demoing or debugging a deployment without requiring the reader to know the domain model
in detail.

## Motivation

Facilitates demoing and debugging of the account features API against a running Nucleus
instance without requiring Postman, curl, or other external tooling. IntelliJ's HTTP
client is already available to all contributors and requires no additional setup.

## Scope boundary

- Covers only the endpoints currently exposed by `AccountFeatureController`.
- Does not cover any other controller or endpoint.
- Does not modify any production code, test code, or build configuration unless a
  minor exclusion is required to prevent detekt or spotless picking up `.http` files
  as lintable artefacts — in which case the change is limited to that exclusion and
  must be noted in Findings.
- Does not introduce new Gradle dependencies.

## Location

Check whether a convention for `.http` files already exists in the repository (e.g.
an existing `http/` or `.http/` directory, or files at the project root). Follow any
existing convention. If no convention exists, create the file at `http/account-features.http`
and note the chosen location in Findings.

## Variables

The file must define an environment variable block or use IntelliJ HTTP client
environment file conventions (`http-client.env.json`) so that at minimum the following
are easily overridden without editing the request bodies:

- `baseUrl` — defaults to `http://localhost:8080`
- `clientId` — the value supplied as the `X-Client-ID` header (used for JPA auditing)

Any classification codes, effective datetimes, or feature values used in request
bodies should be expressed as named variables where it aids readability, but this is
at the author's discretion — the criterion is that payloads are easily modified for
a demo or debugging session.

## Verification

- File opens in IntelliJ and is recognised as an HTTP client file (syntax
  highlighting, run gutter icons visible).
- All requests execute successfully against a local Nucleus instance with PostgreSQL,
  Redis, and Kafka running.
- Variables are defined such that switching to a non-local environment requires only
  an environment selection, not editing of request bodies.
- `./gradlew test`, `./gradlew detekt`, and `./gradlew spotlessCheck` all pass after
  the change.

## Findings

**File location.** No existing `.http` file convention was found in the repository. Files
created at `http/account-features.http` and `http/http-client.env.json` per the task's
fallback directive.

**Effective datetime corrected.** The session prompt's payload guidance specified
`2026-04-01T00:00:00Z` as the example effective datetime. This is a future date relative
to the current wall-clock time (2026-03-23). The resolution query is
`effectiveDatetime <= asAt`; a GET without `asAt` uses `Instant.now()`, so values with a
future effective datetime are not visible. The `.http` file uses `2026-01-02T00:00:00Z`
(start of Q1 2026) so that GET without `asAt` returns the configured values immediately
on a fresh instance. The `interestRate` property carries `@BoundaryGoverned(BUSINESS_DAY_CLOSE)`;
on an instance with no period closure records the boundary check is a no-op, so any past
effective datetime is accepted.

**Classification code prefixes corrected.** The session prompt's payload guidance
specified `SAVE_INAS_2026_Q1Q2` and `LEND_BTRM_2026_Q1Q2` as example classification
codes. These use pre-ADR-011 provisional ledger-side prefixes (`SAVE`, `LEND`). ADR-011
establishes `ASST` and `LIAB` as the only valid ledger-side values. `ClassificationCode.ledgerSide`
calls `LedgerSide.valueOf(value.substringBefore("_"))`, which throws `IllegalArgumentException`
for any prefix other than `ASST` or `LIAB`. The `.http` file uses `LIAB_INAS_2026_Q1Q2` and
`ASST_BTRM_2026_Q1Q2` instead.

**Silent 500 for invalid ledger-side prefix.** When an `IllegalArgumentException` is thrown
from `ClassificationCode.ledgerSide`, it is not caught by `ErrorHandler` (which handles only
`MissingRequestHeaderException`, `NucleusValidationException`, and `NucleusInternalErrorException`).
Spring Boot's `ErrorPageFilter` catches the exception and forwards to `/error` without logging
it at application level, producing a generic 500 with no error log entry. The
`classificationCodeViolation` check that precedes the `ClassificationCode` construction only
validates segment format (four uppercase alphanumeric characters), not that the first segment
is a valid `LedgerSide` value, so the validation does not catch this case.
This is a candidate story: add a `NucleusValidationException` for an unrecognised ledger-side
prefix so the error is surfaced as a structured 400 rather than a silent 500.

**Detekt scope.** Detekt operates only on Kotlin source files (`*.kt`, `*.kts`). Files
in `http/` are never in scope. No configuration change required.

**Spotless scope.** Spotless is configured for three targets: `kotlin {}` (Kotlin sources),
`java {}` (Java sources), and `sql { target("src/main/resources/**/*.sql") }`. None of these
patterns match `http/*.http` or `http/http-client.env.json`. No exclusion change required.
