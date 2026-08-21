<!--
SYNC IMPACT REPORT
==================
Version change: 1.0.1 → 1.0.2
Bump rationale: PATCH — Principle VIII's static-analysis facts brought back in
                line with reality after an explicit user decision (21 Aug 2026)
                adopted Checkstyle and a JaCoCo coverage gate as part of the
                CI-policy alignment with service-cp-crime-hearing-results-
                validator. The principle's requirement — uniform estate
                conventions, warnings never tolerated, narrow reasoned
                suppressions — is unchanged; what changed is the tool set that
                enforces it, and the build/merge checklist that names it.

Modified principles (this amendment):
  - VIII. Estate Conventions — static-analysis bullet rewritten (Checkstyle
    google_checks maxWarnings 0 main-only in `check`; JaCoCo coverage
    verification LINE ≥ 0.88 / BRANCH ≥ 0.85 with named exclusions, ratchet
    semantics; PMD unchanged, still explicit-only). The "required to run
    cleanly before merge" list updated to match: `build` now runs Checkstyle
    and the coverage verification; PMD remains the one analysis it does not.

Previous amendment (1.0.0 → 1.0.1): PATCH — a non-semantic coordinate correction in Principle IV.
                Spring Boot 4.1 ships Jackson 3, whose tree model is
                `tools.jackson.databind.JsonNode`; the principle previously
                named the Jackson 2 class `com.fasterxml.jackson.databind.
                JsonNode` and the Jackson 2 feature constant
                `USE_BIG_DECIMAL_FOR_FLOATS` as if they were the requirement.
                The requirements themselves are unchanged — canonical JSON tree
                inbound, exact BigDecimal round-tripping of monetary values,
                unknown fields surviving, typed records outbound — so no
                existing practice is invalidated and nothing is relaxed. The
                wording is now version-neutral, naming the platform's Jackson
                generation rather than a fixed coordinate.

Modified principles:
  - IV. Canonical JSON In, Typed Models Out — Jackson class coordinate and
    feature constant made version-neutral (see rationale above). No other
    principle text changed in this amendment.

History:
  - 1.0.2 (2026-08-21) Principle VIII static-analysis reality sync: Checkstyle
    + coverage gate adopted by user decision; merge checklist updated.
  - 1.0.1 (2026-08-20) Principle IV Jackson coordinates made version-neutral.
  - 1.0.0 (2026-08-20) Initial ratification. Every principle and section was
    new; there were no prior principles to remove or redefine.

Added sections:
  - Core Principles
      I.    Behaviour-Parity First
      II.   Test-Driven Development
      III.  Message-Contract First
      IV.   Canonical JSON In, Typed Models Out
      V.    SOLID with Ports and Adapters
      VI.   Explicit Failure — Nothing Is Ever Swallowed
      VII.  Privacy in Telemetry
      VIII. Estate Conventions
  - Technology Stack & Deployment
  - Development Workflow & Quality Gates
  - Governance

Removed sections: None.

Templates requiring updates:
  - .specify/templates/plan-template.md       ✅ compatible — the "Constitution
      Check" block is filled per-feature by `/speckit-plan`; no structural
      change required. Plan authors MUST gate on Principles I–VIII.
  - .specify/templates/spec-template.md       ✅ compatible — no
      constitution-specific content; spec authors MUST express behaviour in
      terms of the queue message in and the `add-informant-register` command
      out (Principle III), not in terms of REST endpoints.
  - .specify/templates/tasks-template.md      ✅ updated — the template (and its
      twin, .claude/skills/speckit-tasks/SKILL.md) described test tasks as
      OPTIONAL, contradicting Principle II. Both now state that test tasks are
      mandatory and MUST be ordered before the implementation tasks they guard.
  - .specify/templates/checklist-template.md  ✅ compatible — no changes.
  - CLAUDE.md                                 ✅ aligned — the template's
      "API-First Rule" (all endpoints in `doc/openapi.yaml` first) has been
      replaced with the message-contract rule, matching Principle III: this
      service exposes no business REST API.
  - .claude/rules/*.md                        ✅ aligned — retained as
      quick-reference; this constitution is authoritative where they disagree.

Follow-up TODOs: None. All placeholders resolved.
-->

# service-cp-crime-informant-register Constitution

This service is a lift-and-shift ("Option 2", agreed 19 Aug 2026) of the
informant register Node.js function app into a Spring Boot service on AKS.
It consumes thin hearing-resulted messages from a dedicated Azure Service Bus
queue, rebuilds the informant register for each prosecuting authority, and
POSTs the result to the Results context's existing `add-informant-register`
command. It exists because the function app fails silently; the constitution
below is written to make that failure mode impossible to reproduce.

## Core Principles

### I. Behaviour-Parity First (NON-NEGOTIABLE)

The legacy JavaScript function app under
`cpp-context-azure-legalaidagency/azure-functions/durable-functions/` — together
with its Jest fixtures — is the specification for observable behaviour. Not the
prettier design, not what the code *should* do, not what a reviewer assumes the
business wants. **Bug-for-bug parity is the requirement.**

- Legacy JSON fixtures MUST be copied byte-identical into this repo and used as
  golden files. Every Jest case MUST gain a JUnit twin.
- Known oddities are ported as-is, deliberately. Examples that MUST NOT be
  "tidied up" during the port: group proceedings are **not** skipped (unlike
  other register flows); the major-creditor vocabulary call is made with two
  arguments and always yields empty lists today; first-occurrence-wins
  identifier dedupe; court-extract filtering as written.
- Any intentional divergence MUST be recorded in a reviewed **deviations
  register** (`doc/DEVIATIONS.md`) with: the legacy behaviour, the new
  behaviour, why, who approved it, and the test that pins it. An undocumented
  behaviour change is a defect regardless of whether it looks like an
  improvement.
- Two divergences are pre-approved and already on the register at ratification:
  verified TLS on the payload fetch (the legacy `rejectUnauthorized: false` is
  not ported), and transport reliability (retry, dead-letter, alerting) where
  the legacy code swallowed errors.
- Recorded real hearing payloads MUST be replayed through legacy and new and
  compared; the recorded set MUST include multi-authority hearings, court
  applications, group proceedings, re-shares, and legal-entity defendants. The
  port is done when every twin passes and the recorded set matches or every
  difference is on the register.

**Rationale**: downstream consumers — the `informant_register` table, the 19:00
CSV sweep, GOV.UK Notify recipients, prosecuting authorities — are unchanged and
unaware. The only safe port is one that is indistinguishable from the original
at the boundary. Fixes come later, with business sign-off, as their own change.

### II. Test-Driven Development (NON-NEGOTIABLE)

Red → Green → Refactor for every behaviour change, without exception.

1. Write the failing test first. It MUST run and fail for the *correct* reason
   — the assertion, not a missing class or a compile error.
2. Write the minimum production code to make it pass.
3. Refactor with the test still green.

Because "it really did fail first" cannot be proved from a commit graph, the
evidence is a convention that a reviewer can audit:

- Test tasks precede implementation tasks in every task list, and each test task
  is closed before the implementation task it guards is opened.
- Each task's PR or commit narrative records the observed red run before the
  green run — the failing assertion, quoted.
- Reviewers reject implementation commits whose tests could not have failed
  first: assertions that are tautologically true, tests that assert only that no
  exception was thrown, coverage added in the same breath as the code with no
  red run recorded.

The `qa` reviewer agent gates on that convention. Production code arriving
without an accompanying failing-then-passing test is a FAIL, not a style
comment.

Exempt: pure mechanical refactors (rename, move, extract with no behaviour
change), formatting, and comment-only edits.

**Rationale**: parity (Principle I) is only meaningful if it is executable. A
test written after the code encodes what the code does; a test written before it
encodes what the legacy app did. Only the second one protects the register.

### III. Message-Contract First (NON-NEGOTIABLE)

This service has **no business REST API**. Its contracts are:

- **Inbound** — the message on `informantregister.requests`:
  `{ source, requestId, hearingId, hearingDay, sharedTime, eventType }`.
  Agreed jointly with `cpp-context-results` (the publisher); changes are a cross-team event.
- **Outbound** — the Results context's existing `add-informant-register`
  command. It is **results-owned, fixed, and `additionalProperties: false`**.
  This service adapts to it; it does not negotiate it mid-story.

Rules:

- Both contracts MUST be documented in this repo (`doc/API_CONTRACTS.md`) and
  the inbound message MUST have a JSON schema, versioned with the repo, that
  contract tests assert against.
- A change to either contract is a **cross-team event**: it requires a spec, an
  agreed change with the Results context, and a compatibility plan (consumers
  and producers deploy independently — assume the old shape is in flight).
- The only HTTP this service exposes is Spring Boot Actuator. `doc/openapi.yaml`
  does not describe business endpoints, and adding a business endpoint requires
  a constitution amendment, not just a spec.

**Rationale**: the queue message and the results command are the whole external
surface. Treating them with the discipline other services give an OpenAPI spec
is what keeps a redeploy on either side from silently dropping registers.

### IV. Canonical JSON In, Typed Models Out (NON-NEGOTIABLE)

Inbound hearing payloads (from Redis, or the results-query-api fallback) are
large, sparsely populated, and owned elsewhere. They MUST be handled end-to-end
as the JSON tree model (`JsonNode`) of the Jackson generation the platform
(Spring Boot) provides — currently Jackson 3
(`tools.jackson.databind.JsonNode`) under Spring Boot 4.1:

- No POJO/record mapping of the inbound hearing payload. Unknown fields MUST
  survive untouched; binding to a typed model silently discards what it does
  not know, and this service is not the owner of that shape.
- Jackson MUST be configured with the platform equivalent of
  `USE_BIG_DECIMAL_FOR_FLOATS` so monetary and numeric values round-trip
  exactly — no binary-float drift into a register.
- Inbound trees are **immutable in practice**: never mutate a `JsonNode` you did
  not construct. Derive new nodes; do not edit inputs in place.
- Output is the opposite: everything this service *produces* — register
  fragments, per-authority outbound documents, the `add-informant-register`
  payload, the processed-log rows — MUST be typed Java records, validated
  before submission.

**Rationale**: fidelity in, contract-checking out. `JsonNode` guarantees we
cannot lose a field we did not anticipate; typed records guarantee we cannot
send a field the results command will reject under `additionalProperties:
false`.

### V. SOLID with Ports and Adapters (NON-NEGOTIABLE)

The pipeline is expressed as an application core surrounded by adapters:

```
ASB listener (adapter)
    → DistributionPipeline (application core)
        → IdempotencyGuard        (processed-log port)
        → HearingPayloadSource    (Redis adapter; results-query-api fallback)
        → RegisterTransformer     (pure, no I/O)
        → RegisterSubmissionClient (results add-informant-register adapter)
```

- Transport, payload source, and submission MUST each sit behind an interface
  owned by the core. The core MUST NOT import Azure Service Bus, Redis, HTTP
  client, or JDBC types.
- The transformation stage MUST be pure: JSON in, typed documents out, no I/O,
  no clock, no randomness (inject any of those). It is the parity-critical code
  and MUST be testable with golden files alone.
- Dependency injection MUST be constructor parameters with `private final`
  fields (explicit constructor or Lombok `@RequiredArgsConstructor`).
  Field-level `@Autowired` is forbidden, including in tests.
- Stubbed adapters are a legitimate, temporary state (the CRA-220 walking
  skeleton ships logging no-ops for payload fetch and submission). A stub MUST
  implement the real port interface, MUST log at a level that makes its
  no-op-ness obvious, and MUST NOT be reachable in a production profile once
  the real adapter lands.

**Rationale**: the parity work is in the transformation; the risk is in the
adapters. Separating them lets the golden-file suite run in milliseconds with no
broker, no cache, and no Results context, and lets each adapter be replaced
without reopening ported logic.

### VI. Explicit Failure — Nothing Is Ever Swallowed (NON-NEGOTIABLE)

**No exception is ever caught and ignored.** Not "for robustness", not "to keep
the consumer alive", not in a `finally`, not in a stub.

Every failure path MUST terminate in one of exactly two outcomes:

1. **Retry (abandon)** — a transient failure (connect, IO, 5xx, 429) retried
   with backoff, or the message abandoned so the broker redelivers it.
2. **Dead-letter** — a poison or exhausted message explicitly dead-lettered with
   a reason and description, visible on the DLQ.

**Alerting is required in addition, never instead.** Whichever of the two
outcomes is taken, the failure MUST also produce an ERROR log carrying
`requestId` and `hearingId` and increment a metric an alert fires on (DLQ depth
> 0; failures sustained 15 minutes). An alert is not a way of settling a
message; a message that is only alerted about has not been settled at all.

Specific rules:

- The ASB consumer uses peek-lock with **explicit** `complete` / `abandon` /
  `deadLetter`. Auto-complete is forbidden. A message is completed only after
  the work it represents is durably done.
- `catch` blocks MUST rethrow, wrap-and-rethrow, or perform one of the two
  outcomes above. An empty `catch`, a `catch` whose body is only a `debug`/
  `trace` log, and a swallowed `InterruptedException` (without restoring the
  interrupt flag) are all build-blocking.
- **Service Bus health MUST NOT gate readiness.** A broker blip must not roll
  the pods; queue health is an alerting concern, surfaced as its own metric and
  a non-readiness health group.
- `System.out`, `System.err`, and `printStackTrace()` are forbidden in
  production code and tests; diagnostics go through SLF4J.

**Rationale**: silent failure is the disease this service was commissioned to
cure. The legacy app swallowed submission errors and lost registers with no
signal. A loud, retried, dead-lettered failure is a success of this design; a
quiet one is the only true outage.

### VII. Privacy in Telemetry (NON-NEGOTIABLE)

Logs, metrics, traces, and exception messages MUST NOT carry defendant personal
data at `INFO` level or above — no names, dates of birth, addresses, NINOs,
contact details, or free-text that may contain them.

- The permitted correlation set at `INFO` is: `requestId`, `hearingId`,
  `hearingDay`, `source`, prosecuting-authority code/identifier, counts, and
  timings. Every log line about processing MUST carry `requestId` and
  `hearingId`.
- Whole payloads, register fragments, and outbound documents MUST NOT be logged
  at any level in a deployed environment. Where a payload dump is genuinely
  needed for local diagnosis it goes behind `DEBUG` **and** an explicit
  local-only profile guard.
- Metric labels and dimensions are logs too: never label a metric with anything
  identifying a person.
- Secrets, connection strings, and tokens MUST NOT appear anywhere in output.

**Rationale**: this pipeline handles criminal-court results for named
individuals across the whole estate's log shipping. Correlation IDs are enough
to debug it; personal data in a log index is an incident.

### VIII. Estate Conventions (NON-NEGOTIABLE)

- **Build**: Gradle (wrapper committed). Maven is forbidden.
- **Static analysis**: PMD via `.github/pmd-ruleset.xml` with
  `ignoreFailures = false`, run explicitly as `./gradlew pmdMain` (an `onlyIf`
  keeps it out of `build`; `pmdTest` is disabled). **Checkstyle** (adopted
  21 Aug 2026, reversing the earlier no-Checkstyle stance, by explicit user
  decision as part of the CI-policy alignment with
  `service-cp-crime-hearing-results-validator`): `google_checks` via
  `config/checkstyle/google_checks.xml` and `gradle/checkstyle.gradle`,
  `maxWarnings = 0`, main sources only (`checkstyleTest` disabled), wired into
  `check` and therefore `build`. **Coverage gate**:
  `jacocoTestCoverageVerification` in `check` — LINE ≥ 0.88, BRANCH ≥ 0.85,
  excluding the application entry point and `config/**`; thresholds are a
  ratchet copied from the validator and tuned deliberately, never loosened in
  passing. Warnings are not tolerated as normal. Suppressions MUST be inline,
  narrow, and carry a reason.
- **Package root**: `uk.gov.hmcts.cp`; this service's code lives under
  `uk.gov.hmcts.cp.informantregister`.
- **Commits**: Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`,
  `refactor:`, `test:`).
- **Branches**: Jira-prefixed, `CRA-XXX-short-slug` (Jira project **CRA**).
- **No AI attribution anywhere** — not in commit messages, branch names, PR
  titles or descriptions, code comments, or documentation. No `Co-Authored-By`
  trailers naming a tool, no generated-with footers. All output reads as
  developer-authored work.
- Logging is SLF4J + Logback. `src/main/resources/logback.xml` configures a
  single console appender using `LoggingEventCompositeJsonEncoder`
  (`net.logstash.logback`), so JSON is emitted **unconditionally** — there is no
  `json` Spring profile and no plain-text alternative. MDC is a declared
  provider, which is what puts `requestId` and `hearingId` on every line
  (Principle VII).

**Rationale**: this repo is one of ~70 in the CPP estate and is operated by
people who did not write it. Uniform build, analysis, naming, and history let
them read it the same way they read everything else.

## Technology Stack & Deployment

- **Java**: 25. **Framework**: Spring Boot 4.1, from
  `hmcts/service-hmcts-crime-springboot-template`.
- **Ports**: local `8082`; Kubernetes `4550`.
- **Messaging**: Azure Service Bus queue `informantregister.requests` + its
  dead-letter queue, consumed via `azure-messaging-servicebus`
  `ServiceBusProcessorClient`.
  - Peek-lock, explicit settlement, **`maxDeliveryCount` 5**.
  - Broker **duplicate detection on**; `messageId` = `source:requestId`.
  - **Replay tooling MUST always mint a fresh `messageId`** — deliberate replay
    must not be silently discarded by duplicate detection.
  - `maxConcurrentCalls` starts at 2, matching the Durable Functions throttle
    it replaces.
- **Idempotency**: PostgreSQL processed-log — `processed_request` keyed
  `(source, request_id)`, plus a per-authority `processed_output`. Schema
  migrations via **Flyway**. The POST to `add-informant-register` is not
  idempotent on the Results side, so avoiding duplicate submission is this
  service's responsibility. The honest guarantee: **at-most-once submission in
  all normal operation**, redeliveries and replays included; across a crash in
  the instant between a successful POST and recording it, **at-least-once** —
  the duplicate row is absorbed downstream exactly like a re-share (the 19:00
  sweep dedupes to the latest row per hearing). Strict at-most-once is
  impossible without Results-side idempotency, which is out of scope (frozen
  contract). An ambiguous POST (timeout, unknown outcome) is therefore
  **retried**: a possible duplicate, which is absorbed, is preferred to a
  possible loss, which is silent.
- **Payload source**: Redis (`INT_` keys, both key forms) with a
  results-query-api fallback; verified TLS on both.
- **Outbound**: HTTP POST of `add-informant-register` per prosecuting authority
  to `cpp-context-results`, with retry on connect/IO/5xx/429 and dead-letter on
  exhaustion.
- **HTTP surface**: Spring Boot Actuator only — health, readiness/liveness,
  metrics. No business endpoints (Principle III).
- **Test stack**: JUnit Jupiter 6 (the Boot 4.1 test starter) + Mockito (unit); golden-file/fixture tests for the
  ported transformation; **Testcontainers** — Service Bus emulator and
  PostgreSQL — for integration tests (suffix `*IT`); **WireMock** for the
  Results and reference-data stubs.
- **Observability**: structured JSON logs with `requestId`/`hearingId`; metrics
  for processed/failed and queue + DLQ depth; alerts on DLQ > 0 and on failures
  sustained for 15 minutes.
- **Secrets/identity**: workload identity + Key Vault CSI.
- **Deployment**: AKS via the standard Flux route. This service contains no
  scheduler, no CSV generation, and no GOV.UK Notify code — the 19:00 Results
  sweep is untouched.
- **Out of scope by construction**: SJP hearings (they continue through the NOWs
  function app), the `informant_register` table and schema, and
  `results.prosecutor-results`.

### Current increment — CRA-220 "Informant register - Initial POC"

The walking skeleton only: ASB consumer, idempotency guard, and the port
interfaces with stub adapters (payload fetch and register submission as logging
no-ops), plus actuator and a container build. The transformation port, the Redis
adapter, and the Results POST adapter are later stories. Stubs are governed by
Principle V; the skeleton is still built test-first under Principle II.

## Development Workflow & Quality Gates

- The contract artefacts (inbound message schema, `doc/API_CONTRACTS.md`) MUST
  be updated **before** any code change that affects either contract
  (Principle III).
- Every feature built via spec-kit lives under `specs/NNN-slug/` containing at
  least `spec.md`, `plan.md`, and `tasks.md`. Flow:
  `/speckit-specify → /speckit-plan → /speckit-tasks → /speckit-implement
  → /speckit-analyze`.
- Non-trivial changes flow through `Spec → Write → Code Review → QA →
  Spec-Validate → Fix → Ship`. The reviewer agents (`code-reviewer`, `qa`,
  `spec-validator`) report findings only; they MUST NOT modify code. The primary
  agent or a human applies fixes and re-runs until all three return
  PASS / COMPLIANT. Exempt: markdown-only edits, whitespace/import-only edits,
  and `.claude/rules/*` or `CLAUDE.md` updates.
- Required to run cleanly before merge:
  - `./gradlew build` — compilation, the full test suite, **Checkstyle** and the
    **JaCoCo coverage verification** (both run in `check`). PMD is the one
    analysis `build` does not run.
  - `./gradlew test` — the whole suite; there is no separate `integrationTest` task, so the
    Testcontainers suites run here and need Docker only when those tests are in the selection.
  - `./gradlew pmdMain` — static analysis, failures not ignored. It must be named explicitly: an
    `onlyIf` in `gradle/pmd.gradle` skips it otherwise, and `pmdTest` is disabled.
  - `./gradlew jacocoTestReport` — coverage report.
- Any change to ported logic MUST run the golden-file suite; a golden file is
  only updated in the same commit as a deviations-register entry (Principle I).
- Pull requests: the description MUST state which principle(s) the change
  touches. Any deviation requires explicit written justification in the PR
  description and MUST be flagged in the plan's "Complexity Tracking" section.
- Reviewers MUST specifically look for: a swallowed exception (Principle VI), an
  auto-completed message, a typed model bound over an inbound payload
  (Principle IV), PII in a log line (Principle VII), and production code with no
  preceding failing test (Principle II).

## Governance

This constitution supersedes the informal conventions in `.claude/rules/` and
the template-derived guidance in `CLAUDE.md` — including the template's
"API-First Rule", which does not apply to a service with no business REST API.
Where this document and those files disagree, this document wins; they are
retained as quick-reference material and MUST be kept in sync.

**Amendment procedure**:

1. Propose the change in a feature spec under `specs/`.
2. Bump `Version` per semantic versioning:
   - **MAJOR** — a breaking principle change, removal, or redefinition that
     invalidates existing practice.
   - **MINOR** — a new principle, new section, or materially expanded guidance.
   - **PATCH** — clarifications, wording, typo fixes, or non-semantic
     refinements.
3. Re-run `/speckit-analyze` on every in-flight feature spec to verify it still
   aligns with the amended principles; update or waive as required.

**Compliance expectations**:

- All PRs MUST honour these principles.
- Deviations MUST be explicitly justified in the PR description and, where
  relevant, in the plan's "Complexity Tracking" table.
- Reviewers MUST block merges that silently violate a NON-NEGOTIABLE principle
  without a written waiver.
- Behaviour deviations from the legacy function app are governed by the
  deviations register (Principle I), not by PR discussion alone; a deviation
  merged without a register entry MUST be reverted or registered retrospectively
  with named approval.

**Version**: 1.0.2 | **Ratified**: 2026-08-20 | **Last Amended**: 2026-08-21
