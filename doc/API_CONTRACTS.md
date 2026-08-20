# API Contracts — service-cp-crime-informant-register

## Source of Truth

**The OpenAPI specification is the definitive API contract:**

```
doc/openapi.yaml
```

All endpoints, request/response schemas, status codes, and content types defined in `openapi.yaml` are authoritative. Code MUST match the spec — not the other way around.

## Swagger UI

When the service is running, interactive API documentation is available at:

```
http://localhost:8082/swagger-ui/index.html
```

## Endpoints Summary

None — this service exposes no REST API (actuator health/metrics only); its inbound contract is the ASB queue message, its outbound contract is the results add-informant-register command

<!-- Example:
| Method | Path                  | Status | Description        |
|--------|-----------------------|--------|--------------------|
| GET    | /api/court-rooms      | 200    | List all rooms     |
| GET    | /api/court-rooms/{id} | 200    | Get room by ID     |
| POST   | /api/court-rooms      | 201    | Create new room    |
| PUT    | /api/court-rooms/{id} | 200    | Update room        |
| DELETE | /api/court-rooms/{id} | 204    | Delete room        |
-->

## Request / Response Schemas

Schemas are defined in `doc/openapi.yaml` under `components/schemas`. Each schema maps to a Java record in the `model` package.

| Schema | Java Record | Purpose |
|--------|-------------|---------|
| | | |

<!-- Example:
| CourtRoom              | CourtRoom.java              | Full entity representation |
| CreateCourtRoomRequest | CreateCourtRoomRequest.java | POST request body          |
| ProblemDetail          | Spring ProblemDetail        | RFC 9457 error response    |
-->

## Error Responses

All error responses use **RFC 9457 Problem Details** format (`application/problem+json`):

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Resource with id 123 not found",
  "instance": "/api/resource/123"
}
```

| Status | When |
|--------|------|
| 400 | Invalid request body or parameters |
| 404 | Resource not found |
| 409 | Conflict (duplicate, state violation) |
| 500 | Unexpected server error |

## Content Types

| Direction | Content-Type |
|-----------|-------------|
| Request | `application/json` |
| Response (success) | `application/json` |
| Response (error) | `application/problem+json` |

## Authentication

<!-- Describe authentication method when implemented -->

For PoC/MVP: No authentication (`SecurityConfig` permits all requests).

Production target: Entra ID + JWT via `Authorization: Bearer <token>` header.
