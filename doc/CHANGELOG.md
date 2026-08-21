# Changelog — service-cp-crime-informant-register

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- 2026-08-20 — Repository scaffolded from `hmcts/service-hmcts-crime-springboot-template`
  (Spring Boot 4.1, Java 25, Gradle, package root `uk.gov.hmcts.cp`).
- 2026-08-20 — Spec-kit bootstrap: `.claude/rules/` adapted for this service —
  `design_rules.md` (message-driven ports-and-adapters pipeline, processing state machine,
  queue semantics, idempotency log, parity/deviations rule), `workflow.md` (message-contract
  and golden-parity gates in place of the API-first gate), `technical-rules.md` and
  `technical-default.md` (stack facts, messaging and no-swallowed-exception conventions).
- 2026-08-20 — Project documentation seeded from the agreed Option 2 design (19 Aug 2026):
  `doc/SOLUTION_BRIEF.md`, `doc/TECHNICAL_DESIGN.md`, `doc/API_CONTRACTS.md`.
- 2026-08-20 — **CRA-220 "Informant register - Initial POC" started.** Walking skeleton in
  flight: ASB consumer with peek-lock settlement discipline, `(source, requestId)` idempotency
  guard, ports with stub adapters (payload fetch and register submission as logging no-ops),
  actuator and container build.
- 2026-08-21 — **CRA-220 walking skeleton delivered.** A message now travels the whole path and is
  accounted for at the end of it:
  - **Intake** — Service Bus processor on `informantregister.requests` in peek-lock with
    auto-complete off; exactly one explicit complete / abandon / dead-letter per delivery, and the
    settlement guard is exactly one broker call wide so nothing after it can be reported as a
    refusal. Contract-invalid bodies are dead-lettered before any record exists.
  - **Contract** — `distribution-command.schema.json` parsed into the `DistributionCommand` record,
    dual-validated against parser and schema over a corpus, with unknown fields and out-of-enum
    `source`/`eventType` pinned.
  - **Idempotency** — the `processed_request` log (Flyway `V1`) and a conditional-update claim: at
    most one run in flight per `(source, requestId)`, claims reclaimable after their lease, stale
    runners rejected by owner+token, `FAILED` replay decided by broker message identity, and a
    fingerprint collision dead-lettered with the record untouched.
  - **Store outages** — a store that stops answering suspends intake and abandons the delivery
    rather than burning `maxDeliveryCount`; migration is deferred off context refresh and runs on
    the first successful probe, so nothing is consumed against an unmigrated schema.
  - **Health and telemetry** — the store gates readiness and the queue never does (a broker blip
    must not roll the pods); a passive `servicebus` health component plus
    `informantregister_servicebus_up`; correlation-only MDC with no payload logging, and an ERROR
    log and a named failure metric on every failure path.
  - **Packaging** — Dockerfile, `docker-compose.yml` (Postgres + pinned Service Bus emulator 1.1.2
    sharing one queue definition with the Testcontainers harness) and `scripts/container-smoke.sh`,
    which CI runs as the `Container-Smoke` job.
  - The submission and payload adapters remain deliberate logging stubs; an empty authority set is
    this increment's correct outcome, not a missing step.
- 2026-08-21 — CRA-220 handover written (what the increment still needs outside this repository); maintained outside the repo with the workstream's analysis notes
  (queue and DLQ provisioning, workload identity and Key Vault CSI, Flux/ADO wiring, the GitHub
  remote, and the operability and Results-publisher follow-ups).

### Changed
- 2026-08-21 — Git/CI policies aligned with `service-cp-crime-hearing-results-validator`:
  workflows rebuilt on its `main` (SHA-pinned actions, `team/**` triggers, branch-aware artefact
  versioning via `gradle.properties` `projectVersion`, split Build/Test jobs, Trivy image scan,
  release-notes image coordinates, CodeQL config excluding test sources, secrets-scanner on push);
  branch-protection ruleset added as code (`.github/rulesets/main.json`, import manually);
  CODEOWNERS set to `@hmcts/results-validation-service-team`; Dependabot 14-day cooldown;
  `.editorconfig` and `settings.gradle` added. The validator's `API-Test` job is replaced by an
  unconditional `Container-Smoke` job (`scripts/container-smoke.sh`) — this service has no REST
  surface — and there is no `validate-api-spec-version` gate (no apiSpec dependency).
- 2026-08-21 — Checkstyle adopted (`config/checkstyle/google_checks.xml`, tool 10.25.0,
  `maxWarnings = 0`, main sources only, wired into `check`); existing violations fixed
  (import order, javadoc summaries, one indentation). Supersedes the earlier
  "no Checkstyle in this build" stance — agreed 21 Aug 2026.
- 2026-08-21 — JaCoCo coverage gate added (`jacocoTestCoverageVerification` in `check`:
  LINE ≥ 0.88, BRANCH ≥ 0.85, excluding `Application` and `config/**`), matching the
  validator's ratchet. Thresholds to be re-checked against measured coverage once CRA-220
  development settles.
- 2026-08-20 — `doc/openapi.yaml` reduced to a comment-only stub: this service has no REST API
  (actuator only). Its contracts are the inbound ASB message and the results-owned
  `add-informant-register` command — see `doc/API_CONTRACTS.md`.
