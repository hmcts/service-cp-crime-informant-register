# Changelog — service-cp-crime-informant-register

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- 2026-08-22 — **The submission leg: `add-informant-register` is actually POSTed.** The stub
  submission client is replaced by `adapter/results/`, which posts one command per prosecuting
  authority to the results-owned endpoint at the exact vendor media type, and by the
  `processed_output` half of the processed log, which has existed since V1 and until now stayed
  empty (no new migration — the schema was already complete).
  - The row is claimed **before** the POST, carrying `request_digest` — SHA-256 of exactly the bytes
    sent — and moved to `POSTED` or `FAILED` afterwards, so a POST whose outcome is never learned
    still leaves evidence of what was attempted. The claim and the skip are one conditional upsert
    rather than a read and then a write, because two deliveries of a request can be in flight and a
    `SELECT` is stale the moment it returns. An authority already `POSTED` is skipped, so partial
    progress survives a redelivery or a replay.
  - Retry classification is the one sanctioned behaviour change (`doc/DEVIATIONS.md` #2): connect
    and read failures, dropped connections and 5xx are retried with a doubling wait; 429 is retried
    after the delay the server asked for, capped so a misconfigured server cannot park a run past
    its claim; any other 4xx is a refusal, is never retried, and comes back `NON_TRANSIENT` under
    the new bounded reason `SUBMISSION_REJECTED`. An **ambiguous** outcome is retried, preferring a
    duplicate the 19:00 sweep absorbs over a loss nothing does — and no code or comment promises
    more than that.
  - `AuthoritySubmission` gains the request's key, because `processed_output` is keyed
    `(source, request_id, prosecution_authority_id)` and an authority identifier alone cannot name
    that row.
  - Endpoint, identity and retry policy are typed configuration under `informantregister.results.*`.
    `CJSCPPUID` is the one documented header; because the authorisation scheme is **not** documented
    anywhere, any further header is configuration rather than a guess in code.
  - WireMock joins the build for this: one exact path, one exact vendor media type and a body the
    results-owned schema accepts are not assertable against a mocked HTTP client, which agrees with
    whatever the code does.

### Fixed
- 2026-08-23 — **The startup time budget now covers the whole payload fetch** (second Story 1
  review round):
  - the budget counted the query-side attempts only, so the two cache reads in front of them — the
    dated key and its legacy undated twin, each able to spend a connect and a command timeout —
    were time a run could spend beyond the deadline the budget exists to hold it to. They are
    counted now, and the fetch must be *strictly* shorter than the processing deadline: a fetch
    that fills it exactly leaves the rest of the run nothing, which is the reading
    `DistributionPipeline` and the lease rule already take of that bound;
  - the cache and query-side settings are asked of the live source only. STUB builds neither
    client, so a local stub run is no longer refused startup over a cache address and an attempt
    count that nothing in it will read — the same scope the system-user identity rule already had.
- 2026-08-22 — **Post-review hardening of the payload adapter** (Story 1 review, findings
  re-verified against the function-app source before fixing):
  - the composite adapter no longer catches every `RuntimeException` the cache can raise. The
    catch moves down to the cache adapter, which knows its own technology, and narrows to
    `RedisException` — so a cache outage is still a miss the query side can answer, while a defect
    in this service reaches the pipeline and is recorded instead of being spent on a fallback;
  - two behaviours that differ from the function app are now named in `doc/DEVIATIONS.md` with
    their assertions: reading the legacy undated cache key as a second lookup (entry 4), and
    treating a cache that cannot be *connected* to as a miss rather than as the end of the fetch
    (entry 5). Registered deviation 1, verified TLS, gained the assertion it never had;
  - neither parse failure — a corrupt cached value or a malformed query response — is logged with
    the parser's own words any more; they quote the token they stopped on, and in a hearing
    document that token is defendant data;
  - startup now refuses a payload source that could never fetch: LIVE without the system user
    identity its fallback authorises with, STUB where the deployed credential source is in use
    (constitution Principle V, now that the real adapter has landed), a fallback allowed no
    attempts, a timeout that never expires, a cache with no address or key prefix, and a fallback
    whose worst case outlasts the processing deadline it runs inside;
  - the source-selection tests are twinned against the function app's own Jest fixture, copied
    byte-identical into `src/test/resources/fixtures/`, and the live adapter is now exercised
    through the whole service — cache hit to COMPLETED, and a refused query read to FAILED and
    parked — which no suite covered before.
- 2026-08-22 — **Post-review parity corrections to the ported transformation.** Every finding was
  re-verified against the Node source under
  `cpp-context-azure-legalaidagency/azure-functions/durable-functions/` before it was fixed, and
  four of them were places where the port was **more forgiving than the legacy** — the one direction
  a bug-for-bug port must never drift in, because a payload the function app throws on would have
  produced a register here and sent it to a real prosecuting authority:
  - arrays the legacy dereferences with no `|| []` and no enclosing `if` are now refused when they
    are absent, not read as empty. `Json.dereferencedArray` is the form that says so, and each call
    site names the legacy line it reproduces; `Json.array` keeps the guarded semantics and is used
    only where the legacy guards;
  - a `"courtCentre": null` is refused like a missing one, because `null.welshCourtCentre` is the
    same `TypeError` — it was being read as "not Welsh" and marking the hearing English;
  - a court application whose `judicialResults` is not an array is **skipped**, not refused: the
    legacy test is `judicialResults.length > 0`, and `undefined > 0` is false, so it carries on with
    the rest of the application. The port was failing a hearing the legacy turns into a register;
  - a slash-separated day now formats the way `moment.tz` formats it — through the `new Date(...)`
    fallback, read as UTC and then converted, so an hour later in British Summer Time — instead of
    raising an unclassified `DateTimeParseException`. Verified against the vendored
    `moment-timezone`, and host-time-zone independent;
  - the classification the ports carry is now the branch it was always documented to be: a
    `NON_TRANSIENT` failure is recorded FAILED and dead-lettered at once instead of being handed
    back until the delivery budget ran out and parked under `DELIVERY_LIMIT_EXHAUSTED`. New guard
    transition `recordNonTransientFailure`, and the first producer of `DeadLetterReason.NON_TRANSIENT`;
  - deviations register: new entry **7** records the transformation's own swallowed exceptions, which
    the code had been attributing to entry 2 — that entry covers the final POST only.
- 2026-08-22 — **Second review pass over the submission leg** (findings re-verified against the
  rules and the Node source before fixing; the ones that contradicted a rule were rebutted, not
  applied):
  - `RESULTS_SYSTEM_USER_ID` now reaches something. `application.yaml` documented the variable in a
    comment and bound no key to it, so a deployment that set the identity correctly still refused to
    start with "system-user-id is required". It is bound as `${RESULTS_SYSTEM_USER_ID:}`, in the
    same shape as `RESULTS_BASE_URL`, and the shipped file's binding is now asserted against the
    real file rather than against property values a test invents;
  - the retry policy is validated at startup alongside the claim timings and the credential rule.
    `max-attempts` below one attempted no POST at all and handed every hearing back as an unresolved
    transient failure — silent non-delivery wearing a retry policy's clothes. A negative
    `initial-backoff` and a `max-backoff` below it are refused for the same reason: they fail
    quietly at runtime and loudly at startup;
  - a transport failure is logged with the exception rather than with its class name. The
    classification is what the pipeline settles on; a refused connection, a read that timed out and
    a dropped route are three different investigations, and the bounded reason code the failure is
    reported under cannot carry the difference;
  - the idempotency gate is now proven across the real repository, a real store and a real socket
    (`SubmissionRedeliveryIT`): a redelivery does not re-POST an authority that went, and a
    redelivery after a partial failure repeats exactly the authority that did not. The existing
    suites proved each half against a mock of the other;
  - `doc/DEVIATIONS.md` #8 records the `Retry-After` treatment that #2 implied but did not state.
- 2026-08-22 — **Post-review hardening of the submission leg** (review of the story-3 change,
  findings re-verified against the rules and the contract before fixing):
  - a failure that carries a classification is now settled on it. A refusal from the Results command
    was being caught as an unexpected runtime failure, recorded `UNEXPECTED_FAILURE` and retried to
    exhaustion; it is now parked on the delivery that met it, under the reason it carried
    (`SUBMISSION_REJECTED`) and the dead-letter category `non-transient`. The guard grew
    `recordNonTransientFailure` for it, and the data model's transition table names the row;
  - `POSTED` is terminal in `processed_output`, enforced by the statements rather than by
    convention: a runner whose claim was reclaimed while it worked can no longer move an authority
    the winner had already posted back to `FAILED`, which would have had the next delivery re-claim
    it and POST a second, non-idempotent register;
  - the adapter checks that its outcome writes landed and reports an overlap at ERROR instead of
    discarding the affected-row count;
  - `AuthoritySubmission` carries an `InformantRegisterDocument` rather than a `JsonNode`. The tree
    was a placeholder for a document type that did not exist yet; it exists, and constitution
    Principle IV asks the compiler — not a runtime schema check — to keep an unnameable field out of
    a closed contract;
  - `CJSCPPUID` is required. The gateway refuses to be built without one, so a deployment missing the
    identity fails to start instead of dead-lettering every hearing it is given, one 403 at a time;
  - success is `202 Accepted` and nothing else: any other 2xx is reported non-transient under the new
    `SUBMISSION_NOT_ACCEPTED` rather than marking an authority POSTED for a command nothing enqueued
    (`doc/DEVIATIONS.md` #2 extended to say so);
  - `Retry-After` is matched before it is read, so an unusable header is classified rather than
    raised and caught. The delta-seconds-only rule stands and is now pinned by a test: honouring an
    HTTP-date would measure a remote clock against this pod's, and a server minutes ahead would park
    a run past the claim it holds.
- 2026-08-21 — **Post-review hardening of the walking skeleton** (whole-`src/` review, findings
  independently re-verified before fixing):
  - an unexpected exception inside an admitted run is now recorded through the guard (RETRYING, or
    FAILED + dead-letter on the final permitted delivery) instead of escaping with the run claim
    still live and letting the broker park the message with no record behind it;
  - lock loss is learned from the broker's refusal of the one settlement attempt and counted under
    the lock-loss instrument, replacing the local-clock `lockedUntil` pre-check that skew could
    turn into skipped settlements;
  - only store-outage exception classes suspend intake; a constraint violation or broken statement
    hands its delivery back without stopping the queue;
  - a consumer the broker has never answered no longer ages its startup fault into a healthy
    reading: it keeps one startup grace window and then reports DOWN until first contact;
  - `source` joins `requestId`/`hearingId`/`hearingDay` in the MDC on every message, including the
    contract-invalid path (canonical values only);
  - completing a previously retried request clears `failure_reason`, so a COMPLETED row never
    carries a stale failure (data model updated to state the semantic);
  - the workflow message-contract gate text now matches the closed contract the schema declares —
    unknown extra fields dead-letter; they were never tolerated.

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
