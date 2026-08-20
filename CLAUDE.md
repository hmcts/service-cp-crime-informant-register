# service-cp-crime-informant-register

Consumes hearing-resulted messages from a dedicated Azure Service Bus queue and produces per-authority informant register submissions (Option 2 lift-and-shift of the informant register function app)

## Programme
Crime Common Platform (CPP) — Modern by Default (MbD)
Team: Resulting Assistant

## Stack
- Spring Boot 4.0.0, Java 25, Gradle
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

## API-First Rule
All REST endpoints MUST be defined in `doc/openapi.yaml` FIRST.
Code is generated FROM the spec, not the other way around.
The spec-validator agent checks compliance after implementation.

## Build & Test
```bash
./gradlew build              # Full build + unit tests
./gradlew test               # Unit + E2E tests only
./gradlew integrationTest    # Integration tests (requires Docker)
./gradlew bootRun            # Run locally
```

## Setup
See [SETUP.md](SETUP.md) for first-time configuration steps.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
<!-- SPECKIT END -->
