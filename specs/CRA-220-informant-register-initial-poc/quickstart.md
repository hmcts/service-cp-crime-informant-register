# Quickstart — CRA-220 walking skeleton

## Prerequisites

- Java 25 on `PATH`; Docker running.
- Nothing else — the broker and database are containers.

## One-command end-to-end demo (spec SC-006)

```bash
./gradlew test --tests '*WalkingSkeletonIT'
```

`WalkingSkeletonIT` starts the Service Bus emulator (pinned `1.1.2`, with its SQL Server companion)
and Postgres via Testcontainers, boots the full application context, and drives the skeleton
end-to-end:

1. sends a valid distribution request to `informantregister.requests`;
2. waits for the `processed_request` row to reach `COMPLETED` with reason `no-authorities`;
3. asserts the **payload** stub logged its invocation, the **submission** stub was **not** invoked —
   the skeleton pipeline produces an empty authority set, so zero submissions is the correct
   outcome, not a missing step — and the message left the queue (no redelivery, DLQ empty);
4. re-sends the same request under a fresh messageId and asserts no second pipeline run.

## Interactive local run

`bootRun` runs on the host and **does not inherit the environment defined in `docker-compose.yml`**
— that block configures the `app` container only. Pass the settings explicitly:

```bash
docker compose up -d postgres servicebus-emulator   # queue declared in docker/servicebus-emulator/config.json

export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/informantregister
export SPRING_DATASOURCE_USERNAME=informantregister
export SPRING_DATASOURCE_PASSWORD=informantregister
export INFORMANTREGISTER_SERVICEBUS_CONNECTIONSTRING='Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;'
export INFORMANTREGISTER_SERVICEBUS_QUEUENAME=informantregister.requests

./gradlew bootRun                                   # port 8082
```

Equivalently, without exports:

```bash
./gradlew bootRun --args='
  --spring.datasource.url=jdbc:postgresql://localhost:5432/informantregister
  --spring.datasource.username=informantregister
  --spring.datasource.password=informantregister
  --informantregister.servicebus.connection-string=Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;
  --informantregister.servicebus.queue-name=informantregister.requests'
```

To exercise the simulated transient failure (spec FR-009), add
`--informantregister.stub.payload-failure-mode=TRANSIENT`. It is a local/test switch only — there is
no message field and no endpoint that triggers a failure.

### Health

- **Readiness**: `http://localhost:8082/actuator/health/readiness` — this is the endpoint to check.
  It gates on Postgres only.
- **Liveness**: `http://localhost:8082/actuator/health/liveness`.
- The aggregate `http://localhost:8082/actuator/health` **may report `DOWN` because of the
  `servicebus` component** while readiness is `UP`. That is the designed behaviour (spec FR-011): a
  broker outage must never roll the pods. Judge the service by `/actuator/health/readiness`; read
  the `servicebus` component for queue state.
- Metrics: `http://localhost:8082/actuator/prometheus`.

### Poking at it

- Send a test message: any AMQP-capable client against the emulator connection string above; watch
  the service log for the stub port lines and query `processed_request` in the `informantregister`
  database.
- Stop the broker (`docker compose stop servicebus-emulator`) and confirm readiness stays `UP` while
  the `servicebus` health component reports `DOWN`; start it again and consumption resumes within
  60 seconds with no restart (spec SC-004).
- The emulator does not persist across restarts — queue state is empty after
  `docker compose restart servicebus-emulator`; that is an emulator limit, not a service behaviour.

## Full verification before commit

```bash
./gradlew build              # compile + entire test suite (unit + *IT; Docker needed for the ITs)
./gradlew pmdMain            # static analysis — explicit, not part of build
./gradlew jacocoTestReport   # coverage report
```

All three also run in `.github/workflows/ci-build-publish.yml`.
