# ADR-007: Hypothetical Configuration Query Endpoint

**Date:** 2026-03-20
**Status:** Accepted

## Context

The parameter value hierarchy supports pre-scheduling of configuration changes via
future-dated parameter values. A configurer who registers a new rate effective 2026-04-01
expects the hierarchy to resolve that rate for all accounts at the relevant classification
node from that date. The mechanism works through resolution — Nucleus finds the
appropriate value at the appropriate effective date — but it is invisible until an account
is actually serviced on that date.

This creates a verification gap. A configurer who submits a PUT to
`/account-features/SAVE_INAS_2026_Q1Q2` receives a confirmation that the submission was
accepted and validated. They do not receive a confirmation of what the resolved
configuration will be for an account at that classification code on any given date. The
two are related — a valid submission contributes to the resolved configuration — but they
are not the same. Hierarchical resolution means the effective configuration for an account
is not determined by any single node's values alone; it is the product of a traversal
across multiple levels. A configurer who has correctly submitted values at a leaf node may
still be surprised by what resolves if an ancestor node carries an unexpected value for
the same key.

There is currently no way to inspect resolved configuration without an account. The only
resolution path available to a configurer is through an existing account's effective
configuration query — which includes account-level values, reflects only that account's
specific node attachment, and cannot represent a future-dated or hypothetical scenario.
Opening a test account to verify configuration before opening real accounts is
operationally expensive, pollutes the account record, and cannot serve future-dated
verification (there is no mechanism to open an account "as of a future date").

ADR-003 identified the absence of this endpoint as a material gap in the operational
tooling available after a node transfer: the primary risk of that decision — stale
account-level values masking destination node configuration — is mitigated specifically
by a configurer being able to inspect what would resolve at the destination node without
account-level values, before deciding whether adjustment is needed.

## Decision

Nucleus exposes a read-only endpoint — `GET /account-features/{classificationCode}` —
that returns the resolved account features for a hypothetical account at the given
classification code and effective date. An `asAt` query parameter specifies the effective
date for resolution; it defaults to the current date if not supplied.

The resolution traversal begins at the given classification node. There is no account
node: this endpoint resolves classification-code-level configuration only, with no
account-level values included. This is the correct behaviour for the use case — the
endpoint answers the question "what configuration would an account at this classification
code inherit, before any account-level overrides?", not "what is the resolved
configuration for a specific account?"

The response is the same strongly-typed account features representation that a configurer
receives when querying effective configuration through any other features endpoint. The
underlying parameter key-value system is not exposed. The response is a read-only
projection; it has no side effects.

## Consequences

**Positive:**

- Pre-opening configuration verification is possible without creating an account. A
  configurer who has submitted classification-node configuration can query the resolved
  result before opening any accounts against that code, at the current date or any
  future date for which values have been pre-registered.
- Future-dated configuration verification is supported. A configurer who has registered
  a rate change effective 2026-04-01 can query `?asAt=2026-04-01` to confirm the new
  rate resolves correctly and that no ancestor node carries a conflicting value at the
  same effective date.
- Post-transfer review is supported. A configurer who has transferred an account to a
  new classification node can query the destination code to inspect what the inherited
  configuration will be, making any divergence from the account's existing account-level
  values visible without having to interpret resolution outcomes from a real servicing
  event.
- Historical configuration audit is supported. Querying with a past `asAt` date returns
  the configuration that would have resolved at that date, supporting investigation of
  historical account behaviour and supporting the configurer personas' audit obligations.
- The resolution function already performs hierarchy traversal as its core operation.
  This endpoint exposes a read-only projection of that function without an account
  context — a simplification, not an extension, since account-level values add complexity
  that is absent here.

**Negative:**

- The endpoint widens the account-features API surface. Any change to the resolution
  semantics or to the account features representation must be reflected in both the
  write path and this read path. The contract is broader.
- The response represents hypothetical resolution: it does not include account-level
  values and it may not match the resolved configuration for any specific real account
  at that classification code. The distinction between "what this classification code
  would resolve" and "what this specific account resolves" must be clearly communicated
  in the API contract, or callers will draw incorrect conclusions.
- A configurer who verifies a future-dated configuration and then relies on that
  verification as a guarantee has an incorrect mental model. The endpoint returns a
  snapshot as of the queried date based on currently registered values; a subsequent
  parameter write before that date can change what resolves without invalidating the
  earlier verification response.

**Risks:**

- **False confidence from temporal drift.** A configurer verifies the configuration for
  a classification code today, intending to open accounts against it tomorrow. If a
  parameter value at any level of the hierarchy is changed between the verification query
  and the account opening, the verified snapshot is stale. The endpoint cannot guarantee
  future state — it can only reflect the state of the hierarchy at the moment the query
  is executed, projected forward to the `asAt` date using currently registered values.
  This is expected and correct behaviour, but it must be stated explicitly in the API
  documentation so that configurers treat the response as a point-in-time snapshot, not
  a commitment.

- **Queries for future `asAt` dates reflect current registrations only.** A query with
  `asAt=2026-06-01` returns the configuration that would resolve on 2026-06-01 based on
  parameter values registered as of the query execution time. A new future-dated value
  submitted after the query will change the answer. Configurers using this endpoint to
  pre-validate a future configuration must re-query after any subsequent write that may
  affect the relevant hierarchy path.

- **Interpretation of absent features.** If a key is not configured at any level of the
  hierarchy for the given classification code and effective date, the resolution walk
  returns "no value." The endpoint must represent this state unambiguously in the
  response — a missing feature in the response must be distinguishable from a feature
  that resolves to an explicit absence marker. These two states have different meanings:
  one indicates the feature is not configured in the hierarchy at all; the other
  indicates it has been deliberately suppressed at some level.

## Alternatives Considered

**Do not build the endpoint; require an account for all configuration queries.** A
configurer who wishes to verify resolved configuration opens a test account at the
relevant classification code, queries its effective configuration, and closes the account.
This is rejected on three grounds: it pollutes the account record with test accounts that
have no business meaning; it imposes operational overhead and introduces latency into
what should be a verification step, not an account management workflow; and it cannot
serve future-dated verification, since there is no mechanism to open an account at a
future date and query its resolved configuration as of that date.

**Expose raw parameter key-value pairs rather than the account features representation.**
The endpoint returns the underlying parameter key-value pairs resolved from the hierarchy,
rather than the strongly-typed account features view. This is rejected because the
account-features API is the external contract through which configurers interact with
Nucleus's configuration capabilities. Exposing the internal parameter key-value
representation breaks the abstraction that the account-features API is designed to
maintain — it leaks the internal model to callers who have no use for it and who would
need to interpret raw key-value pairs without the type information and validation that
the features representation provides. The response must be in the same form as all other
account-features API responses.

**Scope the endpoint to real accounts only (`GET /accounts/{accountId}/features?asAt=`).**
A classificationcode-level query is not provided; only per-account queries are supported.
This addresses the historical audit use case and the post-transfer review use case for
existing accounts, but does not address pre-opening verification (there is no account yet)
or future-dated configuration verification in the abstract (verification is always tied
to a specific account's current node attachment). The pre-opening use case — the primary
motivation — is not served. Rejected.