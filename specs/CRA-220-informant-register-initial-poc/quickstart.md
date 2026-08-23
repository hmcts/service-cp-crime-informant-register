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
                                                    # host 5432 must be free — if the CPP dev-env
                                                    # Postgres already holds it, REPLACE the mapping
                                                    # (`ports: !override` in an override file; plain
                                                    # merging keeps both entries and still collides,
                                                    # and a project name does not isolate host
                                                    # ports) and point the URL below at the new one

export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/informantregister
export SPRING_DATASOURCE_USERNAME=informantregister
export SPRING_DATASOURCE_PASSWORD=informantregister
export INFORMANTREGISTER_SERVICEBUS_CONNECTIONSTRING='Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;'
                                                    # REQUIRED: the jar packages no broker
                                                    # credential; without this export startup
                                                    # fails fast ("Set exactly one of ...")
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
  It gates on **two** components, `db` and `intakeStartup`: the processed-log database answering,
  **and** this pod's own gated start having actually completed. A database that replies is not the
  same thing as a service in a position to use it — the deferred migration and the processor start
  sit between them, and either can fail. The broker is deliberately in neither.
- **Liveness**: `http://localhost:8082/actuator/health/liveness`.
- The aggregate `http://localhost:8082/actuator/health` **may report `DOWN` because of the
  `servicebus` component** while readiness is `UP`. That is the designed behaviour (spec FR-011): a
  broker outage must never roll the pods. Judge the service by `/actuator/health/readiness`.
- Health **details are not exposed over HTTP** (`show-details` is left at its default, `never`), so
  the aggregate answers with a status and no component breakdown. Read the queue state from the
  `informantregister_servicebus_up` gauge on `/actuator/prometheus` instead — it is set from the
  same evaluation as the health component, so the two cannot disagree.
- A consumer that has **never** received a delivery reports the `servicebus` component `DOWN` once
  `informantregister.servicebus.health-staleness` (60s) has passed since intake started, whether or
  not the broker is reachable — "never once answered" is deliberately not the same as "idle". Send a
  message and it returns `UP`.
- Metrics: `http://localhost:8082/actuator/prometheus`.

### Poking at it

- Send a test message: any AMQP-capable client against the emulator connection string above; watch
  the service log for the stub port lines and query `processed_request` in the `informantregister`
  database.
- **Broker outage and recovery** (spec SC-004). Stop the broker with
  `docker compose stop servicebus-emulator` and confirm **readiness stays `UP`** — that is the
  behaviour worth checking, and it holds unconditionally. Start it again
  (`docker compose start servicebus-emulator`), **send a message, and watch it be consumed with no
  restart**. Sending is not optional garnish here: a delivery is the only positive evidence that the
  client reconnected, and it is what returns `informantregister_servicebus_up` to `1`.

  **Do not expect the gauge to drop to `0` simply because you stopped the broker.** The queue-health
  signal is deliberately **passive** — nothing polls the broker, because a probe message would cost
  a delivery every time it ran. It moves on three inputs only: a **refused settlement**, a
  **qualifying processor error**, or the **never-answered grace expiry**. An *idle* consumer whose
  broker disappears produces none of the first two: measured against this emulator in Batch E, a
  `ServiceBusProcessorClient` treats a lost connection as retryable and rolls its receive pump
  silently, emitting **no `processError` at all** — five minutes observed with no callback of any
  kind, the first arriving only once the broker came back. So on an idle service the `0` you see
  after stopping the broker is usually the grace rule expiring, not the stop being detected, and a
  service that has been receiving traffic may sit at `1` for some time after the broker is gone.
  This is a limit of what the client reports, not a gap in the indicator — and it is exactly why the
  readiness group never contains the broker.
- The emulator does not persist across restarts — queue state is empty after
  `docker compose restart servicebus-emulator`; that is an emulator limit, not a service behaviour.

## Full verification before commit

```bash
./gradlew build              # compile + entire test suite (unit + *IT; Docker needed for the ITs)
./gradlew pmdMain            # PMD — explicit, not part of build (Checkstyle and the JaCoCo
                             # coverage verification DO run inside build, via check)
./gradlew jacocoTestReport   # coverage report
```

All three also run in `.github/workflows/ci-build-publish.yml`.
