# service-cp-crime-informant-register

The informant register is the daily record of hearing outcomes sent to prosecuting authorities.
Today it is produced by a Node.js Azure Function App triggered by Event Grid, which has no retries,
no dead-letter queue and no alerting — a register that fails to reach Results is lost silently, with
nothing for support to see or replay. This service is the lift-and-shift of that function app onto
AKS: it consumes thin hearing-resulted messages from a dedicated Azure Service Bus queue, fetches the
hearing payload, rebuilds the register for each prosecuting authority, and POSTs the result to the
Results context's existing `add-informant-register` command. The logic is ported bug-for-bug; what
changes is that failure is now loud, retried, dead-lettered and alertable. Everything downstream —
the `informant_register` table, the 19:00 CSV sweep, GOV.UK Notify — is untouched.

| Field     | Value                                                 |
|-----------|-------------------------------------------------------|
| Team      | Resulting Assistant                                   |
| Programme | Crime Common Platform (CPP) — Modern by Default (MbD) |
| Stack     | Spring Boot 4.1, Java 25, Gradle                      |
| Package   | `uk.gov.hmcts.cp.informantregister`                   |
| Ports     | 8082 local / 4550 Kubernetes                          |

## Status

The pipeline is complete and runs end to end: the Service Bus consumer with peek-lock settlement and
the `(source, requestId)` idempotency guard, the hearing payload fetch with its query-API fallback,
the ported transformation — register fragments per prosecuting authority, subscription matching,
outbound document mapping — and one `add-informant-register` POST per authority, each with retry and
dead-lettering on exhaustion. The end-to-end flow has been tested in a lower environment against
real hearing data. It is not yet in production.

Register content is a bug-for-bug port of the function app; every intended difference is a named
entry in [doc/DEVIATIONS.md](doc/DEVIATIONS.md) with its own assertion in the parity harness.

This service exposes **no REST API**. The only HTTP surface is Spring Boot Actuator.

## Prerequisites

- ☕️ **Java 25** on your `PATH`
- 🐳 **Docker**, for the Testcontainers suites and the local dependencies in `docker-compose.yml`

The Gradle wrapper is committed; the project defines its own Gradle version, so use `./gradlew`
rather than a system Gradle.

## Quickstart

```bash
./gradlew build              # Compile + the full test suite + Checkstyle + the JaCoCo coverage gate
./gradlew test               # The whole suite: unit and *IT alike. There is no separate
                             # integrationTest task; the Testcontainers suites run here and
                             # need Docker only when those tests are in the selection
./gradlew checkstyleMain     # Checkstyle (google_checks, maxWarnings 0); also runs in `check`/`build`
./gradlew pmdMain            # PMD static analysis — explicit only; `build` does not run it
./gradlew jacocoTestReport   # Coverage report; jacocoTestCoverageVerification gates `check`
./gradlew bootRun            # Run locally on port 8082
./scripts/container-smoke.sh # Build the image and require it to report readiness within 60s
```

**See it work end to end in one command** — it starts the Service Bus emulator and Postgres
through Testcontainers, boots the whole application and drives a request through it:

```bash
./gradlew test --tests '*WalkingSkeletonIT'
```

`bootRun` needs its local dependencies — Postgres and the Azure Service Bus emulator — running
first:

```bash
docker compose up -d postgres servicebus-emulator
```

The emulator's queues are declared in `docker/servicebus-emulator/config.json`, and the local
connection string uses `UseDevelopmentEmulator=true`. `docker compose up app` runs the service in
its container against the same pair. Everything in the compose file is local-only: the credentials
there are development defaults and must never be reused anywhere else.

`bootRun` runs on the host and does **not** inherit the compose file's environment — that block
configures the `app` container only — so pass the datasource and broker settings explicitly. The
full sequence, the health endpoints to check and how to read the queue's state are in
[the quickstart](specs/CRA-220-informant-register-initial-poc/quickstart.md).

Checkstyle runs against `config/checkstyle/google_checks.xml` with `maxWarnings = 0` as part of
`check`, alongside a JaCoCo coverage gate (`jacocoTestCoverageVerification`).

## Documentation

Design is maintained on Confluence and that page is the single source of truth — the repository
keeps only the documents that code and tests depend on directly.

| Document                                 | Location                        |
|------------------------------------------|---------------------------------|
| Design (source of truth)                 | [Informant Register Service](https://tools.hmcts.net/confluence/spaces/CRA/pages/2004096218/Informant+Register+Service) (space CRA) |
| Contracts (inbound message and outbound command) | [doc/API_CONTRACTS.md](doc/API_CONTRACTS.md) |
| Deviations register (parity)             | [doc/DEVIATIONS.md](doc/DEVIATIONS.md)             |
| Changelog                                | [doc/CHANGELOG.md](doc/CHANGELOG.md)               |
| Pipeline overview                        | [docs/PIPELINE.md](docs/PIPELINE.md)               |

`CLAUDE.md` is the working agreement for this repository; the binding rules live in
[.specify/memory/constitution.md](.specify/memory/constitution.md) and `.claude/rules/`. Where the
constitution and any other document disagree, the constitution wins.

### Contribute to this repository

Please see [CONTRIBUTING.md](.github/CONTRIBUTING.md) for guidelines.

## Licence

This project is licensed under the MIT Licence — see the [LICENSE](LICENSE) file for details.
