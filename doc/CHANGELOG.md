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
