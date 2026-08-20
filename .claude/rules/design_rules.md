# Architecture & Domain Rules

## Layer Architecture

```
Controller → Service → Repository
```

- **Controllers:** HTTP handling ONLY. No business logic. Delegate to services.
- **Services:** All business logic. Transaction boundaries. Orchestration.
- **Repositories:** Data access ONLY. Spring Data JPA or JDBC.

NEVER put business logic in controllers.
NEVER access repositories directly from controllers.

## Domain Model

Entities: DistributionCommand, ProcessedRequest, ProcessedOutput, RegisterFragment, InformantRegisterDocument

### Entity Fields
DistributionCommand: source, requestId, hearingId, hearingDay, sharedTime, eventType; ProcessedRequest: source, requestId, status, attempts; ProcessedOutput: prosecutionAuthorityId, status, postedAt

<!-- Example:
### CourtRoom
| Field        | Type    | Description              |
|--------------|---------|--------------------------|
| id           | UUID    | Primary key              |
| courtHouseId | String  | Parent courthouse ref    |
| roomName     | String  | Display name             |
| capacity     | int     | Seating capacity         |
| isAvailable  | boolean | Current availability     |
-->

## Domain Statuses (if applicable)

RECEIVED, RETRYING, COMPLETED, FAILED (request); POSTED, FAILED (per-authority output)

<!-- Example: SCHEDULED, CONFIRMED, CANCELLED, ADJOURNED, COMPLETED -->

## API Design

**Source of truth: `doc/openapi.yaml`**

- All REST endpoints MUST match the OpenAPI spec paths, schemas, and response codes
- Use `operationId` from spec as controller method names
- All endpoints under `/api/`
- Return `ResponseEntity` with appropriate HTTP status codes
- Use Java records for request/response DTOs — fields MUST match spec schemas
- Error responses use RFC 9457 ProblemDetail (`application/problem+json`)
- Standard verbs: GET (list/detail), POST (create), PUT (update), DELETE (remove)

### Endpoints
None — this service exposes no REST API (actuator health/metrics only); its inbound contract is the ASB queue message, its outbound contract is the results add-informant-register command

<!-- Example:
| Method | Path                  | Purpose           |
|--------|-----------------------|-------------------|
| GET    | /api/court-rooms      | List all rooms    |
| GET    | /api/court-rooms/{id} | Get room by ID    |
| POST   | /api/court-rooms      | Create new room   |
| PUT    | /api/court-rooms/{id} | Update room       |
| DELETE | /api/court-rooms/{id} | Delete room       |
-->

## PoC / MVP Scope (remove once past MVP)

### Build Now
- Core domain model and service layer
- REST API endpoints
- Unit tests + E2E tests
- Health endpoints
- CI/CD pipeline

### Defer
- Database persistence (use in-memory for PoC)
- Resilience4j circuit breakers
- Entra ID security
- Performance dashboards
