# service-cp-crime-informant-register

Consumes hearing-resulted messages from a dedicated Azure Service Bus queue and produces per-authority informant register submissions (Option 2 lift-and-shift of the informant register function app)

## Programme
Crime Common Platform (CPP) — Modern by Default (MbD)
Team: Resulting Assistant

## Stack
- Spring Boot 4.1, Java 25, Gradle
- Package: uk.gov.hmcts.cp.informantregister
- Port: 8082 (local) / 4550 (Kubernetes)

## Key Documentation
| Document           | Location                      |
|--------------------|-------------------------------|
| Solution Brief     | doc/SOLUTION_BRIEF.md         |
| Technical Design   | doc/TECHNICAL_DESIGN.md       |
| API Contract (OpenAPI) | doc/openapi.yaml          |
| API Contracts (docs)   | doc/API_CONTRACTS.md      |
| Changelog          | doc/CHANGELOG.md              |

## Message-Contract Rule
This service exposes NO REST API (actuator only). Its inbound contract is the
`informantregister.requests` queue message; its outbound contract is the
Results-owned `add-informant-register` command (frozen, `additionalProperties: false`).
See `doc/API_CONTRACTS.md`. Contract changes are cross-team events, agreed jointly
with `cpp-context-results`. The spec-validator agent checks contract compliance,
parity-harness presence, and the absence of REST after implementation.

## Build & Test
```bash
./gradlew build              # Compile + the full test suite + Checkstyle + the JaCoCo coverage gate
./gradlew test               # The whole suite: unit and *IT alike — there is no separate
                             # integrationTest task; the Testcontainers suites run here and
                             # need Docker only when those tests are in the selection
./gradlew checkstyleMain     # Checkstyle (google_checks, maxWarnings 0); also runs in `check`/`build`
./gradlew pmdMain            # PMD — explicit only; `build` does not run it (see gradle/pmd.gradle)
./gradlew jacocoTestReport   # Coverage report; jacocoTestCoverageVerification gates `check`
./gradlew bootRun            # Run locally
```

## Setup

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
`specs/CRA-220-informant-register-initial-poc/plan.md` (with `research.md`,
`data-model.md`, `quickstart.md` and `contracts/` alongside it).
<!-- SPECKIT END -->
