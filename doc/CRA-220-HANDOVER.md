# CRA-220 handover — what the walking skeleton still needs outside this repository

The walking skeleton is complete and gated inside this repository: it builds from clean, the whole
test matrix passes, the image starts and reports readiness, and the CI definitions are in place.
None of that puts it in an environment. Everything below is work this repository cannot do for
itself — infrastructure it consumes, wiring it is deployed by, and follow-up tickets whose
prerequisites it ships.

Each item states **who owns it**, **what "done" looks like**, and **what breaks without it**, so
nothing here has to be reconstructed from the code.

---

## 1. Service Bus queue and dead-letter queue — Platform / Andrey

The service consumes one queue and settles every delivery explicitly against it. The queue is not
created by this repository and must exist before a pod is scheduled.

| Setting | Required value | Why this value |
|---|---|---|
| Queue name | `informantregister.requests` | `informantregister.servicebus.queue-name`; the same name is declared in `docker/servicebus-emulator/config.json` so local, CI and deployed agree |
| `maxDeliveryCount` | **5** | Mirrored in `informantregister.servicebus.max-delivery-count`, which the listener uses to recognise the final permitted delivery and park the request in the same transaction. **Change both together or the service parks early or never.** |
| Duplicate detection | **on** | Spec edge case "resubmission reusing the original message identity": the broker discards it and the service never sees it. Cannot be enabled after creation — an Azure Service Bus queue's `requiresDuplicateDetection` is immutable. |
| Duplicate-detection history window | **PT5M or longer** | Wide enough to cover a publisher retry storm; the emulator config uses PT5M |
| Lock duration | PT1M | The SDK renews up to `informantregister.servicebus.max-auto-lock-renew-duration` (5m), which is validated at startup to exceed the 4m processing deadline plus the 30s renewal margin |
| Dead-lettering on message expiration | on | Expiry becomes a countable park rather than a silent drop |
| Dead-letter queue | the queue's own DLQ | Contract-invalid bodies and exhausted requests land here. Nothing else reads it yet — see §6 |
| Session support | **off** | The consumer is not session-aware |

**Without it**: the pod starts, reports readiness `UP` (readiness gates on the store, never the
broker — deliberately), reports the `servicebus` component `DOWN` after the 60-second staleness
window, and consumes nothing. That is the designed failure: loud, and not a crash loop.

## 2. Workload identity and Key Vault CSI — Platform

Locally the broker is addressed with the emulator's development connection string. **Deployed
environments must set `informantregister.servicebus.namespace` instead and leave the connection
string unset** — exactly one of the two may be set and startup fails fast otherwise, which is the
guard that stops a development credential reaching a real namespace.

Needed:

- a workload identity federated to the service account, with **Azure Service Bus Data Receiver** on
  the queue (`DefaultAzureCredential` picks it up; there is no static key path in deployed config);
- Key Vault CSI mounts producing the datasource environment variables — `SPRING_DATASOURCE_URL`,
  `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` — for the `processed_request`
  database. The service migrates its own schema with Flyway on the first successful store probe, so
  the credential needs DDL rights on its own schema;
- `APPLICATIONINSIGHTS_CONNECTION_STRING` for the Java agent configured in `lib/`.

**Without it**: the store credential is the one that matters — the pod comes up, reports readiness
`DOWN` honestly, never starts intake, and never migrates. It will not consume a message it cannot
record.

## 3. Deployment wiring — Platform / Flux + ADO

- Register the repository with the ADO build chain the estate uses (GitHub `ci-draft` /
  `ci-released` → ADO pipeline 460 → `crmdvrepo01.azurecr.io`).
- Add the `springboot-app` release to `cpp-flux-config`, container port **4550** (the service
  defaults to 8082 locally; `SERVER_PORT` selects it).
- **Single replica for CRA-220.** Intake suspension on a store outage is a per-pod decision, so
  additional replicas could still burn deliveries on pods that have not yet noticed the outage.
  Cluster-safe suspension — a shared suspension signal, or KEDA scaling the consumer to zero on
  store health — is deliberately deferred to the KEDA/scale-out story. Set `replicas: 1` explicitly
  rather than relying on a chart default.
- Probes: readiness on `/actuator/health/readiness`, liveness on `/actuator/health/liveness`.
  **Do not point either probe at the aggregate `/actuator/health`** — it includes the `servicebus`
  component, and a broker blip would then roll every consumer pod at once while the queue, the only
  thing actually wrong, stayed exactly as wrong as it was (spec FR-011).
- Chart gaps to raise with Platform: the `springboot-app` chart has no `ScaledObject` template
  (KEDA on queue depth is wanted by the later story), and the readiness group must be able to
  exclude a health component.

## 4. GitHub remote — team

The repository has **no `origin`**: `git remote -v` is empty and all 100 commits are local. The
repository name is still to be confirmed; the working directory name is
`service-cp-crime-informant-register`.

On creation:

- import `.github/rulesets/main.json` as a branch-protection ruleset, then delete
  `.github/rulesets/` including its `DELETE_ME.md`;
- confirm `@hmcts/results-validation-service-team` in `.github/CODEOWNERS` is the intended owning
  team for this service;
- add the organisation secrets the workflows reference — `AZURE_DEVOPS_ARTIFACT_USERNAME`,
  `AZURE_DEVOPS_ARTIFACT_TOKEN`, `HMCTS_ADO_PAT`, `HMCTS_CP_ADO_PAT`, `GITLEAKS_LICENSE`,
  `HMCTS_CP_GITLEAKS_REGEX_INTERNAL_URL`.

**Without it**: none of the CI gates have ever executed on a runner. Everything in this repository
has been verified locally only.

## 5. Operability follow-up ticket — to raise on the CRA board

This closes the named waiver in `doc/DEVIATIONS.md` #3, which is time-boxed and expires when the
operability story lands. The signals the alerts are built from all ship in CRA-220; the wiring does
not.

1. **Alert rules and dashboards** over the shipped instruments —
   `informantregister_deadlettered_total{reason=…}`, the failure counters,
   `informantregister_servicebus_up`, `informantregister_intake_suspended` — plus Azure Monitor's
   native Service Bus `DeadletteredMessages` metric for DLQ **depth**, which service code
   deliberately does not poll. The intended alerts are DLQ depth > 0, and failures sustained over
   15 minutes.
2. **Bounded settlement calls.** `complete` / `abandon` / `deadLetter` on
   `ServiceBusReceivedMessageContext` block on an underlying reactive call with **no timeout of the
   SDK's own**. The settlement guard in `InformantRegisterMessageListener` is exactly one broker
   call wide and turns a refusal into a counted, logged decision — but it cannot bound a call that
   never returns. A broker that accepts the TCP connection and then stops answering would hold the
   processor thread indefinitely. Give the settlement an explicit deadline and treat expiry as a
   refusal, which the existing path already handles correctly (the outcome is durably written
   *before* settlement is attempted, so a redelivery meets a record that already knows the answer).
3. **DLQ-replay CLI.** There is no supported way to get a parked message back. The behaviour it
   must drive already exists and is tested: a `FAILED` request replayed under a **fresh broker
   message identity** transitions `FAILED` → `RECEIVED` with `attempts` preserved and runs again,
   while the *same* exhausted message coming round again stays `FAILED` and is re-dead-lettered
   (spec FR-007, `FailedReplayIT`). The tool is the missing half.
4. **Runbook**: how to read `processed_request`, what each `completion_reason` and dead-letter
   reason code means, and when a replay is safe.

## 6. Results-side publisher ticket — drafted separately

Nothing publishes to `informantregister.requests` yet. `cpp-context-results`
(results-event-processor) must publish one message per resulted regular hearing against
`src/main/resources/contracts/distribution-command.schema.json` — six fields, `additionalProperties:
false` — with:

- `requestId` **deterministic** from `hearingId`, `hearingDay` and `sharedTime`, so a republish of
  the same share carries the same id and a genuine re-share produces a new one;
- the broker `messageId` set, since duplicate detection and the `FAILED`-replay rule both read it;
- `source: RESULTS` and `eventType: Hearing_Resulted` only — SJP stays in the NOWs function app.

Any change to that schema is a cross-team change and must be agreed with the Results team first.

## 7. Cutover, once the real adapters land

Out of scope for CRA-220 — the submission and payload adapters are still stubs, so there is nothing
to cut over to yet — but the shape is fixed and worth carrying forward: the switch from the Node.js
function app is **exclusive, with no parallel running**, because both paths POST into the same
`informant_register` table and running both would duplicate rows. Rollback is switching the
consumer back to the function app.

---

## Appendix A — verification evidence at handover

Recorded at T047 against the finished skeleton, so a reviewer can check the claim rather than take
it. Every row of the plan's test matrix, the test that proves it, and its result.

**Gates, run on 21 August 2026 against `4c08962` plus this batch's documentation changes:**

| Gate | Command | Result |
|---|---|---|
| Full build from clean (compile, whole suite, Checkstyle, coverage verification) | `./gradlew clean build` | **BUILD SUCCESSFUL** in 4m 5s — **352 tests, 0 failures, 0 errors, 0 skipped** |
| PMD | `./gradlew pmdMain` | **clean**; five suppressions, all narrow and each carrying its reason inline |
| Coverage, measured against the `check` gate's own scope (`Application` and `informantregister/config/**` excluded) | `./gradlew jacocoTestReport` | **LINE 97.33 %** (655/673) against a 0.88 threshold · **BRANCH 88.55 %** (116/131) against 0.85. Whole-report, no exclusions: LINE 97.11 %, BRANCH 89.32 % |
| Container smoke against the finished skeleton | `./scripts/container-smoke.sh` | **PASS** — readiness `UP` inside the 60-second budget; teardown clean. The same script is the `Container-Smoke` job in `ci-build-publish.yml`, which `Provider-Deploy` depends on |
| Quickstart, both routes | `quickstart.md` | one-command demo green; interactive route booted, readiness `UP`, a real message consumed end to end to a `COMPLETED` / `no-authorities` record |

**Every row of the plan's test matrix — the named test exists, and it passes:**

| Spec item | Layer | Named test | Exists | Tests | Result |
|---|---|---|---|---|---|
| FR-001 exactly one explicit settlement per delivery | U, SB | `MessageListenerSettlementTest`<br>`QueueSettlementIT` | yes<br>yes | 11<br>3 | PASS<br>PASS |
| FR-002 six-field contract validation | U | `DistributionCommandParserTest`<br>`DistributionCommandSchemaCorpusTest` | yes<br>yes | 26<br>71 | PASS<br>PASS |
| FR-003 invalid → immediate DLQ, no record, no attempt | SB | `ContractValidationDeadLetterIT` | yes | 4 | PASS |
| FR-004 durable processed-log, attempts semantics | PG | `IdempotencyGuardIT`<br>`ProcessedLogDurabilityIT` | yes<br>yes | 16<br>1 | PASS<br>PASS |
| FR-005 state-machine transitions | PG | `IdempotencyGuardIT` | yes | 16 | PASS |
| FR-006 COMPLETED delivery acknowledged, no run | PG, E2E | `IdempotencyGuardIT`<br>`WalkingSkeletonIT` | yes<br>yes | 16<br>2 | PASS<br>PASS |
| FR-007 FAILED replay by message identity | PG | `FailedReplayIT` | yes | 5 | PASS |
| FR-008 at most one run in flight; claim reclaimable | PG | `ClaimContentionIT`<br>`ClaimReclamationIT`<br>`StaleRunnerRejectionIT` | yes<br>yes<br>yes | 4<br>4<br>13 | PASS<br>PASS<br>PASS |
| FR-009 failure recorded, retried, then parked | U, SB | `DistributionPipelineTest`<br>`DeliveryExhaustionIT` | yes<br>yes | 21<br>1 | PASS<br>PASS |
| FR-010 pipeline invoked through the ports | U, E2E | `DistributionPipelineTest`<br>`WalkingSkeletonIT` | yes<br>yes | 21<br>2 | PASS<br>PASS |
| FR-011 store gates readiness, queue never does | SB | `ReadinessPolicyIT` | yes | 12 | PASS |
| FR-012 correlation fields, no PII at INFO+ | U, E2E | `TelemetryPrivacyTest`<br>`TraceabilityIT` | yes<br>yes | 10<br>2 | PASS<br>PASS |
| FR-013 container image starts healthy; CI definitions present | CS | `Container smoke` | n/a | — | PASS |
| FR-014 actuator-only HTTP surface | U | `HttpSurfaceTest`<br>`ActuatorIntegrationTest` | yes<br>yes | 3<br>3 | PASS<br>PASS |
| FR-015 store outage → abandon + suspend intake | PG, SB | `StoreOutageIT` | yes | 3 | PASS |
| FR-016 settlement-failure edges, lock loss | U, PG | `SettlementFailureEdgeTest`<br>`StaleRunnerRejectionIT` | yes<br>yes | 13<br>13 | PASS<br>PASS |
| FR-017 ERROR log + failure metric on every failure path | U, E2E | `ProcessingMetricsTest`<br>`FailureSignalIT` | yes<br>yes | 25<br>3 | PASS<br>PASS |
| FR-018 fingerprint collision → DLQ, record untouched | U, PG | `RequestFingerprintTest`<br>`IdempotencyCollisionIT` | yes<br>yes | 10<br>7 | PASS<br>PASS |
| SC-001 zero silent loss — every message accounted for | E2E | `MessageAccountingIT` | yes | 7 | PASS |
| SC-002 zero duplicate processing in normal operation | PG, E2E | `ClaimContentionIT`<br>`WalkingSkeletonIT` | yes<br>yes | 4<br>2 | PASS<br>PASS |
| SC-003 parked after exactly 5 deliveries; replay → 6 attempts | SB | `DeliveryExhaustionIT`<br>`FailedReplayIT` | yes<br>yes | 1<br>5 | PASS<br>PASS |
| SC-004 ready < 60s; consumption resumes < 60s after queue returns | CS, SB | `Container smoke`<br>`QueueOutageRecoveryIT` | n/a<br>yes | —<br>1 | PASS<br>PASS |
| SC-005 trace a request end-to-end from logs alone | E2E | `TraceabilityIT` | yes | 2 | PASS |
| SC-006 one documented command demonstrates the skeleton | E2E | `WalkingSkeletonIT`<br>`quickstart.md` | yes<br>n/a | 2<br>— | PASS<br>PASS |
| Edge: crash between run completion and outcome write | PG | `CrashWindowIT` | yes | 4 | PASS |
| Edge: unknown fields in an otherwise valid message | U, SB | `DistributionCommandSchemaCorpusTest`<br>`ContractValidationDeadLetterIT` | yes<br>yes | 71<br>4 | PASS<br>PASS |
| Edge: `source`/`eventType` outside agreed values | U | `DistributionCommandSchemaCorpusTest` | yes | 71 | PASS |
| Edge: resubmission reusing the original message identity | SB | `DuplicateDetectionIT` | yes | 1 | PASS |
| Edge: processed-log store unavailable | PG, SB | `StoreOutageIT` | yes | 3 | PASS |
| Edge: store unavailable across many retry intervals | PG, SB | `ProlongedStoreOutageIT` | yes | 1 | PASS |
| Edge: contract-invalid message arriving during a store outage | SB | `StoreOutageIT` | yes | 3 | PASS |
| Edge: crash after RECEIVED, before the run completes | PG | `ClaimReclamationIT` | yes | 4 | PASS |
| Edge: same key, different immutable fields | PG | `IdempotencyCollisionIT` | yes | 7 | PASS |
| Edge: RETRYING request redelivered after a long gap | PG | `IdempotencyGuardIT` | yes | 16 | PASS |
| Startup with the queue already down | SB | `StartupWithQueueDownIT` | yes | 1 | PASS |
| Stale runner finishing after reclamation | PG | `StaleRunnerRejectionIT` | yes | 13 | PASS |


## Appendix B — spec quality checklist, re-verified

`specs/CRA-220-informant-register-initial-poc/checklists/requirements.md` was re-read against the
finished skeleton at T047 and every statement still holds. Nothing on it moved during
implementation, which is the point of checking:

- **Content quality** — the spec still names no language, framework or API; the queue mechanics it
  does name (delivery limit, duplicate detection, DLQ, message identity) are the externally
  observable contract of the service's trigger, which the checklist already records as a deliberate
  boundary rather than an implementation leak.
- **Requirement completeness** — no `[NEEDS CLARIFICATION]` markers; FR-001…FR-018 and
  SC-001…SC-006 are unchanged since the Round 2 gate, and each is now discharged by a named test in
  Appendix A.
- **Feature readiness** — the retained boundaries are still true of the code: the processed-log
  store technology was chosen at planning (Postgres), and `processed_output` remains **schema-only**
  because the stub pipeline produces no outputs. No rows are written to it, exactly as specified.
- Implementation findings were recorded where they belong — `research.md` §8 for the measured
  queue-health amendment and `plan.md` for the JDBC `socketTimeout` — rather than by editing the
  spec. **No behavioural rule in `spec.md` was changed during implementation.**

`doc/DEVIATIONS.md` is unchanged: it is the **parity** register against the legacy function app, and
nothing in this batch touches parity. The health-signal amendment is a deviation from `research.md`
§8's design assumption, not from legacy behaviour — the legacy function app emits no health signal
of any kind — and it is recorded in `research.md` where a reviewer of that decision will find it.

## Appendix C — known local-environment gotcha

Both `docker-compose.yml` and therefore `scripts/container-smoke.sh` bind Postgres to host port
**5432**. On a developer machine already running the CPP development environment that port is taken
by its own Postgres container, and the stack fails to start with `port is already allocated`. Start
it under its own compose project name with the container's 5432 mapped to a spare host port —
`COMPOSE_FILE` accepts a colon-separated override for the smoke script, which takes no arguments of
its own — and adjust the datasource URL to match. Both files are correct as written for a clean
machine and for the CI runner; this is a note for CPP developers, not a defect.
