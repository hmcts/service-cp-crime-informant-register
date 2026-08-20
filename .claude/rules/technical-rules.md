# Coding Conventions — MOJ / CPP Standard

## Dependency Injection

- Constructor injection ONLY — NEVER use @Autowired on fields
- All injected fields MUST be `private final`
- Use Lombok @RequiredArgsConstructor OR explicit constructor

```java
// CORRECT
private final CourtRoomService courtRoomService;

public CourtRoomController(CourtRoomService courtRoomService) {
    this.courtRoomService = courtRoomService;
}

// WRONG — never do this
@Autowired
private CourtRoomService courtRoomService;
```

## DTOs and Data Classes

- Java records for ALL DTOs — immutable by design
- Records for request bodies: `*Request` (e.g., `CreateCourtRoomRequest`)
- Records for response bodies: `*Response` (e.g., `CourtRoomResponse`)
- Use sealed interfaces for polymorphic types

## Error Handling

- Custom exceptions extending `RuntimeException`
- `@ControllerAdvice` for global exception handling
- Return `ProblemDetail` (RFC 9457) for all error responses
- NEVER swallow exceptions silently — always log or rethrow

## Enums and Routing

- Use Java enums for fixed value sets (statuses, types, categories)
- Switch expressions for routing — compiler enforces exhaustive coverage
- Include a `fromName(String)` or `fromValue(String)` factory method

## Logging

- SLF4J with Logback (via Spring Boot starter)
- MDC context: correlationId on every request
- LogstashEncoder for structured JSON in production (spring profile `json`)
- NEVER log sensitive data (tokens, passwords, PII)

## Naming Conventions

| Component    | Pattern                | Example                  |
|--------------|------------------------|--------------------------|
| Service      | `*Service`             | `ListingService`         |
| Controller   | `*Controller`          | `CourtRoomController`    |
| Repository   | `*Repository`          | `CourtRoomRepository`    |
| DTO (in)     | `*Request`             | `CreateCourtRoomRequest` |
| DTO (out)    | `*Response`            | `CourtRoomResponse`      |
| Exception    | `*Exception`           | `CourtRoomNotFoundException` |
| Config       | `*Configuration`       | `WebClientConfiguration` |
| Test         | `*Test` / `*IT`        | `CourtRoomServiceTest`   |

## Testing Conventions

- JUnit 5 + Mockito for unit tests
- MockMvc for controller tests
- WireMock for external service stubs (use `dynamicPort()`)
- TestContainers for integration tests (suffix `*IT`)
- Test commands: `./gradlew test` (unit + E2E), `./gradlew integrationTest` (Docker)
