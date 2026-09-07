# Service Identity

- **Service:** service-cp-crime-informant-register (deployment/release name `informantregister-service`)
- **Description:** Consumes thin hearing-resulted messages from a dedicated Azure Service Bus queue,
  fetches the hearing payload, runs a like-for-like port of the informant-register function-app
  pipeline, and POSTs one `add-informant-register` command per prosecuting authority back to
  `cpp-context-results`. Option 2 lift-and-shift, agreed 19 Aug 2026.
- **Programme:** Crime Common Platform (CPP) — Modern by Default (MbD)
- **Team:** Resulting Assistant
- **Organisation:** HMCTS / Ministry of Justice
- **Jira:** project `CRA` — current story **CRA-220** "Informant register - Initial POC"

## Technology Stack

| Component        | Value                                                                 |
|------------------|-----------------------------------------------------------------------|
| Framework        | Spring Boot 4.1                                                       |
| Language         | Java 25                                                               |
| Build tool       | Gradle (NEVER Maven)                                                  |
| Template origin  | `hmcts/service-hmcts-crime-springboot-template`                       |
| Root package     | `uk.gov.hmcts.cp` (service code under `uk.gov.hmcts.cp.informantregister`) |
| Local port       | 8082                                                                  |
| K8s port         | 4550                                                                  |
| HTTP surface     | **Actuator only — no REST API**                                        |
| Inbound transport| Azure Service Bus queue `informantregister.requests` (+ DLQ), `azure-messaging-servicebus` `ServiceBusProcessorClient` |
| Payload source   | Redis (`INT_` keys) with results-query-api fallback *(later story)*   |
| Outbound         | `POST add-informant-register` → `cpp-context-results` command API *(later story)* |
| Database         | PostgreSQL — service-owned processed-log; **Flyway** migrations (never Liquibase) |
| Testing          | JUnit 5 + Mockito + AssertJ; Testcontainers (`servicebus-emulator`, Postgres); WireMock |
| Static analysis  | PMD (`.github/pmd-ruleset.xml`, explicit `pmdMain`); Checkstyle (`config/checkstyle/google_checks.xml`, in `check`) |
| CI/CD            | GitHub Actions → ADO Pipeline 460 → `crmdvrepo01.azurecr.io`; deploy via Flux (`springboot-app` chart) |

## Constraints

- NEVER use Maven or Spring Initializr; NEVER scaffold from scratch — the repo comes from the crime
  Spring Boot template
- All code in `uk.gov.hmcts.cp.informantregister`
- **No REST API.** Do not add controllers, `springdoc`, or an OpenAPI spec. The inbound contract is
  the queue message; the outbound contract is the results-owned `add-informant-register` command
- **ASB health must never gate readiness** — a broker blip must not restart the pod
- Ports-and-adapters: the application layer depends on interfaces only; Azure/Redis/HTTP types live
  in adapters
- **Bug-for-bug parity** with the Node function app; deviations only via `doc/DEVIATIONS.md`
- **TDD is mandatory** — failing test first, on every commit
- **NEVER swallow an exception** — silent failure is the defect this service exists to remove
- No defendant PII at `info` level or above
- Conventional commits; **no AI attribution anywhere** (commits, code, docs, PRs)
- Secrets via Key Vault CSI + workload identity — no static keys, no committed connection strings

## Build & Test Commands

```bash
./gradlew build                # Full build (compile + tests; run pmdMain for static analysis)
./gradlew test                 # Unit + integration tests
./gradlew bootRun              # Run locally (port 8082)
./gradlew pmdMain              # PMD static analysis
./gradlew jacocoTestReport     # Coverage report

# Single test
./gradlew test --tests "uk.gov.hmcts.cp.informantregister.application.DistributionPipelineTest"
```

## Key Documentation

| Document                | Location                  |
|-------------------------|---------------------------|
| Design (source of truth) | [Informant Register Service](https://tools.hmcts.net/confluence/spaces/CRA/pages/2004096218/Informant+Register+Service) (Confluence, space CRA) |
| Contracts (in + out)    | `doc/API_CONTRACTS.md`    |
| Changelog               | `doc/CHANGELOG.md`        |
| Agreed Option 2 design  | `~/moj/analysis/results-distribution/InformantRegister/option2-implementation-page.md` |
| Current-state deep dive | `~/moj/analysis/results-distribution/InformantRegister/service-cp-crime-informant-register-design.md` (§2 = what must be ported) |
