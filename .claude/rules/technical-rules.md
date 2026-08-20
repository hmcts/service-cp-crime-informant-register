# Coding Conventions — MOJ / CPP Standard

## Dependency Injection

- Constructor injection ONLY — NEVER use `@Autowired` on fields
- All injected fields MUST be `private final`
- Use Lombok `@RequiredArgsConstructor` OR an explicit constructor
- The application layer injects **port interfaces**, never adapter classes

```java
// CORRECT
private final HearingPayloadSource hearingPayloadSource;
private final IdempotencyGuard idempotencyGuard;

public DistributionPipeline(HearingPayloadSource hearingPayloadSource,
                            IdempotencyGuard idempotencyGuard) {
    this.hearingPayloadSource = hearingPayloadSource;
    this.idempotencyGuard = idempotencyGuard;
}

// WRONG — never do this
@Autowired
private RedisHearingPayloadAdapter adapter;
```

## DTOs and Data Classes

- Java records for ALL value types — immutable by design
- `DistributionCommand` (inbound message) is a record; parse it from `JsonNode`, validate explicitly
- **JsonNode-canonical inbound, typed outbound**: the hearing payload stays a Jackson tree behind a
  typed facade; only what this service *produces* (the `add-informant-register` body) is a typed
  record tree
- `ObjectMapper` configured with `USE_BIG_DECIMAL_FOR_FLOATS`; outbound serialisation
  `@JsonInclude(NON_NULL)`
- Use sealed interfaces for polymorphic types (e.g. pipeline outcomes)

## Error Handling

- Custom exceptions extending `RuntimeException`; classify at the throw site as transient or
  non-transient (e.g. `TransientPipelineException` / `NonTransientPipelineException`)
- **NEVER swallow exceptions.** No empty catch blocks, no catch-and-log-and-continue, no returning a
  success value from a catch block. Catch only to classify and rethrow, or to map to a persisted
  `FAILED` state that is then explicitly dead-lettered
- The message listener is the only place that converts an exception into a settlement decision
- No `@ControllerAdvice`, no `ProblemDetail` — there is no HTTP request surface to map errors onto

## Messaging

- Peek-lock, auto-complete disabled; exactly one explicit `complete()` / `abandon()` / `deadLetter()`
  per message on every path
- `maxDeliveryCount` 5; `maxConcurrentCalls` 2 to start
- `messageId` = `"{source}:{requestId}"`; broker duplicate detection on; replay tooling always mints a
  fresh `messageId`
- Persist state **before** settling
- ASB processor health is registered as a liveness/details indicator only — **never** in the
  readiness group

## Persistence

- Flyway migrations at `src/main/resources/db/migration/V<n>__<snake_case_description>.sql`
- Migrations are additive and forward-only; never edit an applied migration
- The processed-log insert is the idempotency claim — a unique-constraint violation means duplicate
  delivery, and is handled, not logged as an error

## Enums and Routing

- Java enums for fixed value sets (`RequestStatus`, `OutputStatus`, `EventType`)
- Switch expressions for routing — the compiler enforces exhaustive coverage
- Include a `fromValue(String)` factory when parsing wire strings; unknown values are an explicit
  non-transient failure, never a silent default

## Logging

- SLF4J with Logback (via the Spring Boot starter)
- Lombok `@Slf4j` or `private static final Logger log = LoggerFactory.getLogger(...)` — the only
  allowed forms
- MDC on every message: `requestId`, `hearingId`, `source` (cleared in a `finally`)
- `LogstashEncoder` emits structured JSON on the `json` Spring profile
- NEVER use `System.out.println`, `System.err.println`, or `Throwable#printStackTrace()`
- NEVER log secrets, tokens, or connection strings
- **NEVER log defendant PII at `info` or above** — no names, addresses, dates of birth, ASNs, URNs.
  Identifiers only

## Imports

- NEVER use wildcard imports (`import java.util.*`) — always explicit imports

## Naming Conventions

| Component        | Pattern            | Example                          |
|------------------|--------------------|----------------------------------|
| Application svc  | `*Pipeline` / `*Service` | `DistributionPipeline`     |
| Port (interface) | capability noun    | `HearingPayloadSource`, `RegisterSubmissionClient` |
| Adapter          | `*Adapter`         | `RedisHearingPayloadAdapter`, `StubRegisterSubmissionAdapter` |
| Message listener | `*MessageListener` | `InformantRegisterMessageListener` |
| Repository       | `*Repository`      | `ProcessedRequestRepository`     |
| Entity           | domain noun        | `ProcessedRequest`               |
| Record (in)      | `*Command`         | `DistributionCommand`            |
| Record (out)     | `*Document` / `*Request` | `InformantRegisterDocument` |
| Exception        | `*Exception`       | `TransientPipelineException`     |
| Config           | `*Configuration` / `*Properties` | `ServiceBusConfiguration`, `ServiceBusProperties` |
| Test             | `*Test` / `*IT`    | `DistributionPipelineTest`       |

## Testing Conventions

- JUnit 5 + Mockito + AssertJ for unit tests; `@ExtendWith(MockitoExtension.class)`
- `@Nested` classes with `@DisplayName` for grouped scenarios
- Method naming: `{action}_{scenario}_should_{expectation}`
- The application layer is tested with plain mocks — **no Spring context**
- Testcontainers for integration tests (suffix `*IT`): `servicebus-emulator` for the consumer,
  Postgres for the processed-log
- WireMock for external HTTP stubs (use `dynamicPort()`), asserting exact CPP vendor media types
- **Golden-parity tests**: Jest fixtures copied byte-identical into `src/test/resources/fixtures/`;
  one JUnit twin per Jest case; comparison field-order-insensitive, array-order-sensitive,
  BigDecimal-tolerant; registered deviations asserted explicitly
- TDD: write the failing test first, see it fail for the right reason, then implement
- Logging in tests: SLF4J only
- Test commands: `./gradlew test` runs the whole suite — unit, integration and `*IT` classes alike,
  since there is no separate `integrationTest` task; the Testcontainers suites run under `test` and
  need Docker only when those tests are in the selection. `./gradlew build` = compile + `test`; it
  adds no static analysis. PMD is explicit: `./gradlew pmdMain` (an `onlyIf` in `gradle/pmd.gradle`
  skips it unless it is named on the command line, and `pmdTest` is disabled). There is no
  Checkstyle in this build.
