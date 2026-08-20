# Technical Design — service-cp-crime-informant-register

## Overview

| Field            | Value                                       |
|------------------|---------------------------------------------|
| Service Name     | service-cp-crime-informant-register                              |
| Package          | uk.gov.hmcts.cp.informantregister              |
| Framework        | Spring Boot 4.0.0                           |
| Java             | 25+                                         |
| Build            | Gradle                                      |
| Port             | 8082 (local) / 4550 (K8s)                   |
| Database         | PostgreSQL 18-alpine (or in-memory for PoC) |

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────┐
│  CONTROLLER LAYER (thin — HTTP binding only)                 │
│  *Controller — REST endpoints, request validation            │
│  ↓ delegates to                                              │
├─────────────────────────────────────────────────────────────┤
│  SERVICE LAYER (business logic)                              │
│  *Service — orchestration, domain rules, transactions        │
│  ↓ calls                                                     │
├─────────────────────────────────────────────────────────────┤
│  REPOSITORY LAYER (data access)                              │
│  *Repository — Spring Data JPA / JDBC / in-memory            │
├─────────────────────────────────────────────────────────────┤
│  DOMAIN MODEL (records, enums)                               │
│  Java records for entities and DTOs                          │
├─────────────────────────────────────────────────────────────┤
│  CROSS-CUTTING CONCERNS                                      │
│  SecurityConfig, JacksonConfig, logback-spring.xml           │
└─────────────────────────────────────────────────────────────┘
```

## Package Structure

```
uk.gov.hmcts.cp.informantregister
├── config/          # Spring configuration classes
├── controller/      # REST controllers
├── service/         # Business logic
├── repository/      # Data access
├── model/           # Domain records, enums, DTOs
└── exception/       # Custom exceptions
```

## API Design

**API contract source of truth:** `doc/openapi.yaml`

All REST endpoints MUST match the paths, schemas, and response codes defined in the OpenAPI specification. See [API Contracts](API_CONTRACTS.md) for detailed endpoint documentation.

## Domain Model

Entities: DistributionCommand, ProcessedRequest, ProcessedOutput, RegisterFragment, InformantRegisterDocument

<!-- Add entity details here — fields, relationships, invariants -->

DistributionCommand: source, requestId, hearingId, hearingDay, sharedTime, eventType; ProcessedRequest: source, requestId, status, attempts; ProcessedOutput: prosecutionAuthorityId, status, postedAt

## Key Components

<!-- List and describe each production class -->

| Class | Layer | Purpose |
|-------|-------|---------|
| | Controller | |
| | Service | |
| | Repository | |
| | Model | |

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | 8082 | HTTP port |
| | | |

## Testing Strategy

| Test Type | Framework | Command |
|-----------|-----------|---------|
| Unit | JUnit 5 + Mockito | `./gradlew test` |
| E2E | MockMvc + WireMock | `./gradlew test` |
| Integration | TestContainers | `./gradlew integrationTest` |

## Security

- Entra ID integration (deferred for PoC — `SecurityConfig` permits all)
- JWT validation via `io.jsonwebtoken:jjwt`
- CJSCPPUID authorisation header where required

## Deployment

- Container: Docker (Dockerfile from template)
- Registry: GHCR → ACR
- Orchestration: Kubernetes (Helm chart)
- CI/CD: GitHub Actions → Azure DevOps Pipeline 460
