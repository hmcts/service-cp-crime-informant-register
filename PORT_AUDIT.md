# Informant Register Port Audit

Review of `service-cp-crime-informant-register` — the Spring Boot lift-and-shift of the
`InformantRegisterOrchestrator` durable-function chain in `cpp-context-azure-legalaidagency`.

| | |
|---|---|
| **HEAD reviewed** | `b3ba369` |
| **Date** | 2026-09-01 |
| **Main source** | 13,249 lines (5,820 code) |
| **Test source** | 29,114 lines · 1,622 passing · 9 skipped |
| **Parity corpus** | 386 recorded Node cases |
| **Scope** | build health, parity fidelity, architecture, maintainability, second-line supportability |

## How to read the provenance marks

- **✓ verified** — I read the cited source and confirmed the claim myself.
- **○ reported** — surfaced by a review pass and plausible on its face, but not independently
  confirmed line-by-line. Treat these as leads to check before ticketing, not as established defects.

Severity: 🔴 Blocker · 🟠 Major · 🟡 Minor · 🟢 Suggestion

---

## 1. Build health

### 🔴 `./gradlew build` does not complete locally — reproduced twice ✓

```
> Task :test FAILED
> java.io.EOFException
BUILD FAILED in 4m 12s
```

All 1,622 tests pass; the worker JVM then dies during shutdown and fails the task.

Two consequences:

1. The dead worker leaves a truncated `build/test-results/test/binary`. Because
   `gradle/test.gradle:5` sets `failFast = true`, Gradle reads that file on every subsequent run,
   so each following `./gradlew test` fails in **one second** with the same opaque error out of
   `Test.getPreviousFailedTestClasses` until someone deletes the directory by hand. Nothing in the
   output hints at that.
2. The test worker runs at Gradle's default `-Xmx512m`; no `maxHeapSize`, `maxParallelForks` or
   `forkEvery` is set anywhere in `gradle/*.gradle` or `gradle.properties`.

**CI on this SHA is green**, so this is a local/macOS developer-experience failure rather than a
broken pipeline — but it means the build command documented in `CLAUDE.md`, the Checkstyle gate and
the JaCoCo coverage gate never complete on a developer machine.

**Fix:** `maxHeapSize = '2g'` in `gradle/test.gradle`; reconsider the `failFast` default so a
crashed run does not wedge the next one.

---

## 2. Correctness

### 🔴 A held claim burns all five deliveries in seconds, leaving no `FAILED` row ✓

`application/IdempotencyGuard.java:255-267` — `claimOrHandBack` returns
`Abandon(CLAIM_NOT_ACQUIRED)` unconditionally.
`application/DistributionPipeline.java:106-117` — every non-`Run` admission is passed straight
through; `delivery.finalPermittedDelivery()` is consulted only inside `runUnder`, never on an
admission path.

Claim lease is **5 minutes** (`application.yaml:61`); `max-delivery-count` is **5**
(`application.yaml:51`). Azure Service Bus makes an abandoned message available immediately —
`e2e/QueueSettlementIT.java:180-194` documents exactly this, observing delivery counts `0,1,2,3,4`
inside a 20s window.

**Interleaving:** a pod dies (or a message lock expires) while a run holds the claim.
`claim_expires_at` sits five minutes in the future. The redelivery finds a live claim →
`CLAIM_NOT_ACQUIRED` → abandon → immediate redelivery → same answer, four more times, in under a
second. The budget is exhausted roughly 4m59s before the claim would have lapsed.

**Consequence:** the broker dead-letters under *its own* reason. `processed_request` stays
`RECEIVED` with a live claim indefinitely — no `FAILED` status, no failure reason, no
`exhausted_message_id`, no `deadlettered` metric. This is precisely the "silent parking the state
machine exists to prevent" that `DistributionPipeline.java:122-128` claims cannot happen, and it is
the exact disease the rewrite exists to cure.

Same shape applies to `RECORD_ABSENT`, `STALE_RUNNER` and `REPLAY_NOT_ADMITTED`.
`CrashWindowIT` misses it because it calls `ProcessedLogTestSupport.expireClaim(...)` to
fast-forward the lease rather than letting redelivery race it.

**Fix:** consult the delivery budget on admission paths — on the final permitted delivery, record
`FAILED` and dead-letter rather than abandoning.

### 🔴 The processing deadline is checked once, before the submission loop, and never inside it ✓ — **FIXED (loop check)**

`application/DistributionPipeline.java:163-187`:

```java
if (clock.instant().isBefore(deadline)) {
    for (final AuthoritySubmission submission : submissions) {
        submissionClient.submit(submission);   // no clock check, N times
    }
    outcome = completed(claim, submissions);
}
```

With shipped defaults each authority can consume `max-attempts 4 × (connect 5s + read 30s)` plus
capped back-offs before the loop moves on. Payload fetch and the now-subscriptions read can already
have consumed most of the `4m` deadline before the check is even reached. Three authorities against
a degraded Results runs minutes past both the deadline and the `5m` lease.

`config/PropertiesValidator.java:145-148` explicitly declines to budget the steps jointly, and
validates nothing at all about the submission leg's duration — `validateTheRetryPolicyCanPost`
checks only that attempts ≥ 1.

**Consequence:** once the lease lapses mid-POST, a redelivery reclaims the request and
`persistence/ProcessedOutputRepository.java:47-59` (`claimPending`) grants the new runner every
authority not yet `POSTED` — so both runners POST. `add-informant-register` is not idempotent, so
duplicate register rows become a load-dependent certainty rather than the rare crash-window case
the design owns and honestly documents.

**Fix:** check the deadline before each `submit`, aborting with `PROCESSING_DEADLINE_EXCEEDED`; add
a validator rule that `payload worst-case + refdata worst-case + (results worst-case × expected max
authorities)` fits inside `processing-deadline`.

**Status — first half done, second half outstanding.** `DistributionPipeline.submitWithin` now tests
the deadline before every POST and aborts transiently with `PROCESSING_DEADLINE_EXCEEDED`, naming how
many registers are already out; the authorities already `POSTED` are skipped on the redelivery, so
only the outstanding ones repeat (`DistributionPipelineTest.DeadlineReached`). The **validator rule
is not written**: it needs an expected-maximum-authorities figure, which is a config value this
service does not have and a number nobody has yet measured. Until it exists, a badly chosen
`results` retry policy is caught at runtime by the loop check rather than at startup — degraded, not
silent, but still later than it should be.

### 🔴 Unguarded `ObjectNode` cast turns a shape variation into five wasted deliveries ✓

`pipeline/DefendantContextBuilder.java:524-525`, reached unguarded from `:456-460` and also
`:148`, `:216`, `:349`, `:377`, `:419`:

```java
private static ObjectNode copyOf(final JsonNode judicialResult) {
    return (ObjectNode) judicialResult.deepCopy();
}
```

- A `defendantJudicialResults` entry with no `judicialResult` member: `Json.at` yields Java `null`;
  `Json.truthy(null, "isDeleted")` is `false`, so the branch **is** entered; `copyOf(null)` → NPE.
- An explicit `"judicialResult": null`, a `null` array element, or a non-object element →
  `ClassCastException`.

Neither is a `TransformationFailedException`, so `DistributionPipeline.java:153-158` records it
**TRANSIENT** + `UNEXPECTED_FAILURE` → abandon → all five deliveries burned → broker DLQ with a
useless reason. The legacy's own `TypeError` maps to an immediate non-transient park.
`DefendantContextBuilderTest.java:320-376` covers only present / deleted / unknown-defendant.

### 🟠 A message with no `messageId` can never reach `FAILED` ○

`inbound/InformantRegisterMessageListener.java:512-517` takes `message.getMessageId()` verbatim;
ASB does not assign one. `RECORD_FAILED` (`persistence/ProcessedRequestRepository.java:119-127`)
writes it into `exhausted_message_id`, and
`db/migration/V1__create_processed_log.sql:68-69` carries
`CHECK (status <> 'FAILED' OR exhausted_message_id IS NOT NULL)`.

With a null id, `recordFailed` throws `DataIntegrityViolationException` from *inside* a `catch`
clause, so no sibling catch sees it; it escapes to the listener's generic handler → abandon →
repeat until the broker DLQs. The row is never terminal.

Related NULL bug: `REPLAY_FAILED` (`:138-152`) uses `exhausted_message_id <> :messageId`, which
evaluates to `NULL` (not true) whenever either side is null → `REPLAY_NOT_ADMITTED` loop.

The design rule says the service "must not depend on `messageId` alone" — here the FAILED
transition depends on it entirely. No test publishes without `setMessageId`.

### 🟠 One authority's refusal permanently strands every authority after it ○

`application/DistributionPipeline.java:178-180` is a plain loop with no per-authority error
containment. Authority 3 gets a 400 → request `FAILED` + dead-letter. Authorities 4 and 5 were
never attempted and have **no `processed_output` rows at all**. A replay skips 1 and 2 as `POSTED`,
re-attempts 3, meets the same 400, and stops again — so 4 and 5 are unrecoverable through the
supported replay path, with the row saying only "FAILED".

### 🟡 Other correctness items ○

- `adapter/results/ResultsRegisterSubmissionClient.java:90-99` — the catch is narrowed to
  `SubmissionFailedException`, so any other RuntimeException out of `gateway.post` leaves the row
  `PENDING` and unfinished, contradicting the class's own guarantee at `:28-30`.
- `inbound/InformantRegisterMessageListener.java:109-118` — the total catch lives in `decide()`;
  the `storeGate.storeAvailable()` condition and the `storeUnavailable()` branch sit outside it, so
  a non-`DataAccessException` from the probe, or a `BeansException` from a closing context, returns
  without settling.
- `inbound/ConsumerLifecycleController.java:232-239` — `processor.stop()` does not wait for running
  callbacks, so shutdown can leave `claim_owner` live for the full lease, which then triggers the
  first blocker above on redelivery.
- `adapter/payload/LettuceHearingPayloadCache.java:127-132` — connection overwritten on reconnect
  without closing the old handle.
- `config/PropertiesValidator.java:409-413` — rejects only a *negative* `initial-backoff`; at `0`
  all four attempts fire back-to-back against a struggling Results.

---

## 3. Parity with the Node original

The port is unusually faithful. `SubscriptionRules` was verified line-for-line against
`SubscriptionsService.js`; the defendant-context builder, the case/offence/result mappers,
`RecipientMapper`, and moment's non-strict date tokeniser (including the ISO-vs-`new Date()` split
and the BST hour shift) all check out. The `AxiosRetryWrapper` rule is correctly ported into both
GET adapters. What follows is what the 386-case corpus cannot reach.

### 🟠 A lone JSON-null `orderedDate` invents a hearing date ✓

`pipeline/OrderedDates.java:78-82` short-circuits at one element and calls `textOf` (`:95-97`),
which maps a `NullNode` to Java `null`. That reaches `pipeline/HearingDates.java:380-383`
`toLondon(null)`, which returns `ZonedDateTime.now(clock)` — the "absent input means now" rule
meant for an absent `sharedTime`.

Node: `[...new Set([null])].sort(cmp)` never calls the comparator for one element, so `sorted[0]`
is `null`, `moment(null)` is invalid, and the fragment carries the literal `"Invalid dateZ"` —
which the consumer's `date-time` schema rejects.

- **Legacy** → no register filed.
- **Port** → a register filed, **stamped with the wall clock at run time**.

That is the one direction a bug-for-bug port must not drift in, and the contradiction is
self-documented: the class javadoc at `:38-41` states the whole reason ordered dates travel as
nodes is that *"`Json.text` collapses 'absent' and 'null' onto the same Java `null`, and the two
are the difference between a register and a lost hearing"* — and `textOf`, ten lines below,
performs exactly that collapse.

Cheaper variant of the same root cause: `pipeline/RegisterBuilder.java:232`
`dates.localDate(sittingDay)` on a `"sittingDay": null` returns *today's* London date, which can
spuriously match an `orderedDate` where the legacy's `"Invalid date"` never would.

Not asserted anywhere: `OrderedDatesTest.compares_an_explicit_null` covers a null only *with a
second element*, and no corpus case contains `"orderedDate": null`.

### 🟠 The two-digit-year pivot is implemented, then not used on one of two date paths ✓ — **FIXED**

`pipeline/HearingDates.java:246-259` — `asDayMonthYear` matches the year with `\d{1,4}`
(`:108-109`) and hands it straight to `Integer.parseInt`. moment applies `parseTwoDigitYear` when
the `YYYY` token consumed exactly two digits, and this class **already implements that rule** as
`inACentury` (`:336-342`) — wired only into the other parse path at `:320`.

A `durationStartDate` / `durationEndDate` of `"26/02/19"` (`pipeline/ResultDataMapper.java:118-121`):

- **Legacy** → `2019-02-26T00:00:00Z`
- **Port** → `"0019-02-26T00:00:00Z"` onto the wire

`HearingDatesTest.java:461-506` tests four-digit years only.

**Fixed.** `asDayMonthYear` now resolves its year through `inACentury`, the same helper the ordering
path uses — the pivot belongs to moment's tokeniser rather than to either format string, so both
paths need it. Confirmed against the `moment` 2.30.1 vendored with the function app, through
`DateService.formatDateAndGetLocalDateTime`, rather than against moment's documentation:

| Value | Legacy | Port before | Port now |
|-------|--------|-------------|----------|
| `26/02/19` | `2019-02-26T00:00:00Z` | `0019-02-26T00:00:00Z` | `2019-02-26T00:00:00Z` |
| `1/2/68` | `2068-02-01T00:00:00Z` | `0068-02-01T00:00:00Z` | `2068-02-01T00:00:00Z` |
| `1/2/69` | `1969-02-01T00:00:00Z` | `0069-02-01T00:00:00Z` | `1969-02-01T00:00:00Z` |
| `26/02/9` | `0009-02-26T00:00:00Z` | `0009-02-26T00:00:00Z` | unchanged |
| `26/02/019` | `0019-02-26T00:00:00Z` | `0019-02-26T00:00:00Z` | unchanged |

The rule is a **token-width** test and not a magnitude one: exactly two digits map into a century,
every other width is the year as written — so a one- or three-digit year still resolves to the ninth
and nineteenth centuries, absurd as that reads, and the last two rows are pinned by their own case
(`HearingDatesTest.FormattedLocalDateTime.leaves_other_year_widths_as_written`) so a later "tidy-up"
cannot quietly widen the rule. No `doc/DEVIATIONS.md` entry: this closes a divergence rather than
declaring one.

### 🟠 `Json.at` used where the legacy dereferences ○

Deviation 7 says "where the legacy throws, this refuses", and `OffenceMapper` follows it. These do
not: `RegisterBuilder.java:139,154,156,259,275,281`,
`DefendantContextBuilder.java:314-317,410-411`, `VocabularyBuilder.java:129`.

A `courtApplication` missing `applicant`/`subject`, a `prosecutionCase` missing
`prosecutionCaseIdentifier`, or a `courtOrderOffence` missing `offence` is a `TypeError` in Node —
the whole hearing yields nothing — but is silently skipped here and a register ships. In the paths
traced this is masked downstream, so it may not be a demonstrated output divergence today; the
masking is incidental and depends on the fragment having at least one defendant with at least one
case. Worth aligning rather than relying on.

### 🟡 `fileName` renders a null authority code as `undefined` ○

`pipeline/AggregationMapper.java:114-119` maps a Java `null` to the six letters `undefined`. In
JavaScript `'_' + undefined` is `"_undefined"` but `'_' + null` is `"_null"`.

- **Legacy** → `InformantRegister_null_2021-03-11.csv`
- **Port** → `InformantRegister_undefined_2021-03-11.csv`

This is the only string-concatenation site in the tree, so deviation 11(a)'s "null becomes an
omitted key" reasoning does not cover it.

### 🟡 Deviations register hygiene ✓

- `InformantRegisterQueueTrigger/index.js:17` is cited as source evidence in **three** places —
  `doc/API_CONTRACTS.md:70`, `doc/DEVIATIONS.md:26`, and inside the canonical, frozen
  `src/main/resources/contracts/distribution-command.schema.json:50`. That directory does not exist
  anywhere in the Node repo; the only trigger for this flow is `InformantRegisterEventGridTrigger`.
  The substance of entry 16 is sound; the citation is fiction, and it sits in the file that is
  supposed to be authoritative.
- Deviations entry 12 is the only entry with no asserting test, which `.claude/rules/workflow.md`
  gate 5 requires. Its scope also does not cover the same absent-vs-null collapse applied to
  **authority ids** in `RegisterBuilder.java:136-151`, where a `LinkedHashMap` produces one
  fragment for two authorities that a JS `Set` keeps separate.
- 🟡 `pipeline/Json.java:63` calls `value.stringValue()`, which in Jackson 3 **throws** for a
  non-String non-null node. A payload with a numeric `offenceTitle` therefore raises an
  unclassified exception treated as TRANSIENT, where every other unreadable-payload condition is
  non-transient. Reachability is limited by the public model's typing. ○

---

## 4. Second-line support

The stated purpose of the whole rewrite is that the Node original swallowed failures and lost court
registers. Judged on whether an on-call engineer can find out what happened.

### 🔴 At 2am, `/actuator/health` tells you nothing and you cannot raise the log level ✓

`src/main/resources/application.yaml:172-194`:

- `management.endpoint.health.show-details` is unset, so it defaults to `never`.
- `loggers` is not in `exposure.include` (only `health,info,metrics,prometheus`).
- Readiness spans `db,intakeStartup` and returns a bare `{"status":"DOWN"}` with **no indication
  which of the two is down**.

Every detail deliberately built into the indicators is therefore unreachable in a deployed pod:
`ServiceBusHealthIndicator` assembles `condition`, `lastErrorAt`, `lastTrafficAt`,
`stalenessWindow`, `intakeStartedAt`; `IntakeStartupHealthIndicator` distinguishes `started` /
`awaiting-store` / `no-consumer-configured`. The repo's own quickstart concedes the point
(`specs/CRA-220-.../quickstart.md:78`: *"the aggregate answers with a status and no component
breakdown"*).

And with `loggers` unexposed, the diagnostic detail that does exist — almost all of it at DEBUG —
cannot be turned on during an incident.

**Fix (two lines):** `show-details: when-authorized`, and add `loggers` to the exposure list.

### 🟠 No submission, payload or run-duration metrics ○

`config/ProcessingMetrics.java:30-39`. Absent:

- **Submission outcomes.** `adapter/results/ResultsCommandGateway.java:220-249` classifies every
  response into accepted / 429 / 5xx / refused / non-202-2xx and increments nothing. "Is Results
  refusing registers?" is unanswerable; there is no failure rate and no visible rise in 429s.
- **Payload source.** No cache hit / miss / error / fallback-used counter.
- **Run duration.** No timer, so nothing shows runs creeping toward the deadline — the leading
  indicator for both blockers in §2.
- **Age of oldest unprocessed request / live claims.** Nothing.
  `stale_runner_rejections_total` fires only *after* a stuck claim has been reclaimed and its work
  discarded.

Note also that counters register lazily on first increment, so a healthy pod exposes no
`*_failures_total` series at all — alert rules need `absent()` / `or vector(0)` handling.

### 🟠 A total Redis outage is invisible ○

`adapter/payload/LettuceHearingPayloadCache.java:87-93` — a `RedisException` is logged at WARN,
treated as a cache miss, with no metric and no health component. Every hearing then falls through
to the results-query fallback (`CachedHearingPayloadAdapter.java:70`). If the fallback holds, every
request completes and the service reports perfect health while its cache tier is dead and it is
putting 100% of hearing reads onto the Results query API — a good way to take Results down too.

Same shape at `:112` for an unparseable cached payload.

### 🟠 `PIPELINE_TRANSIENT_FAILURE` collapses four unrelated causes ○

The same code is written to `processed_request.failure_reason` for: payload unavailable from both
sources; submission attempts exhausted with the outcome unresolved; an interrupt during a
submission back-off; and the stub's injected failure. Given a hearingId from a ticket, the row says
"it failed transiently" and cannot distinguish a cold cache, a dead Redis and a dead Results —
three answers that route to three different teams.

`TRANSFORMATION_FAILED` has the same problem, and the exception's `detail` string — which is a
fixed literal written by this service and contains no producer or defendant text — is never logged
anywhere.

### 🟠 The processed log erases its own failure history ○

`persistence/ProcessedRequestRepository.java:98-106,138-152`:

- `RECORD_COMPLETED` sets `failure_reason = NULL`.
- `RECORD_RETRYING` overwrites the previous reason.
- `REPLAY_FAILED` replaces rather than appends `audit_note`, so two replays destroy the first note.

"This register arrived four hours late on Tuesday" leaves a row reading
`COMPLETED, completion_reason=authorities-submitted, failure_reason=NULL, attempts=6` — the only
surviving evidence is `attempts > 1`, and the logs may have rolled.

`processed_output` has the mirror-image gap: `.claude/rules/design_rules.md` specifies
`prosecutionAuthorityCode`, `registerDate`, `fileName`, `responseCode` and `postedAt`; **none exist
in the schema** (`db/migration/V1__create_processed_log.sql:76-103`). So you cannot see what
Results actually answered, when, or for which register date and filename — reconciliation has to be
done by digest alone. The gateway already has the status at `:221` and discards it.

### 🟠 Failures are reported by type only; almost nothing carries a stack trace ○

`grep getMessage src/main/java` returns nothing. Only three sites pass a `Throwable` to SLF4J. The
"report by type, never by text" rule is sound where the text is producer- or defendant-derived, but
it is applied indiscriminately — including where the code's own comments say otherwise.
`config/ServiceBusHealthIndicator.java:163` logs `type` and `condition` and drops the failure, so a
`DefaultAzureCredential` problem yields
`type=com.azure.messaging.servicebus.ServiceBusException condition=UNAUTHORIZED` and nothing
further: no tracking id to give Microsoft support. `logback.xml:16-18` already renders an
`exception` field — the plumbing exists and is unused.

Similarly `inbound/ConsumerLifecycleController.java:318-331` catches a failed intake start and logs
only the class name, with no metric. A Flyway checksum mismatch therefore produces
`type=FlywayValidateException` every 10s forever, with no indication which migration, and the pod
is on no dashboard: `intake_suspended` stays `0` because `AWAITING_STORE` is deliberately not a
suspension.

### 🟡 No runbook; the log is not indexed for incident queries ○

The **replay rule** is well specified (`doc/API_CONTRACTS.md:129-170`) and — genuinely good —
enforced by the guard rather than by operator memory. But there is no *procedure*: no commands, no
tooling, no script. An on-call has a documented invariant and no way to act on it.

The only index is `(hearing_id, hearing_day)`. "Everything FAILED today", "anything stuck in
RECEIVED for over an hour", "what did we park this week" are all sequential scans — and all are
where an incident starts. Add `(status, updated_at)`.

A runbook needs to answer: which of `db`/`intakeStartup` is down; the exact SQL for "was hearing X
processed?" and how to read each status; the replay procedure end-to-end; the operator mistakes
that are possible (reusing an exhausted `messageId`, editing the body, hand-editing a row); what
each metric means and what to do when it moves; explicitly that `servicebus_up = 0` must **not**
trigger a restart; and how to recognise "intake stalled but pod healthy".

---

## 5. Architecture & maintainability

### 🟠 Disabling intake silently disables schema migration, and readiness still reports UP ✓

`config/DeferredFlywayMigration.java:28-41` replaces the Flyway strategy with a no-op
**unconditionally** — no profile, no `@ConditionalOnProperty`. The only thing that then runs
migrations is `ConsumerLifecycleController.migrateOnce()`, and that bean exists only under
`inbound/ServiceBusConsumerConfig.java:42-45`'s
`@ConditionalOnProperty(prefix="informantregister.consumer", name="enabled", havingValue="true")`.

So `informantregister.consumer.enabled=false` against a real datasource means the schema is never
migrated, while `config/IntakeStartupHealthIndicator.java:47-52` answers UP with
`intake=no-consumer-configured`.

The condition is `matchIfMissing = true`, so the default path is safe and only an explicit opt-out
triggers this — a latent trap rather than an active outage, which is why this is Major and not
Blocker. Only `ConfigurationValidationTest.java:109` sets the flag today.

**Fix:** put the same condition on `DeferredFlywayMigration` so that with intake disabled Flyway
migrates normally at context refresh; make the indicator report DOWN when a `DataSource` exists but
no lifecycle controller does.

### 🟠 The repo's own orientation documents describe an increment that no longer exists ✓

- `README.md:21-27` tells a new joiner the current state is a walking skeleton whose "ported
  transformation pipeline, the Redis payload adapter and the Results submission adapter are later
  stories". **All three are built, wired and tested.**
- `.claude/rules/design_rules.md` repeats it in eleven places (`:17-18,56-58,70-71,91-92,211-219`)
  and `.claude/rules/technical-default.md:26-27` does the same. These are the files that steer
  every future change — and every Claude Code session.
- `specs/CRA-220-.../tasks.md` has 47 tasks, none ticked, against work that is plainly done.

The same rules file names five `ProcessedOutput` columns that do not exist, states the per-authority
statuses are `POSTED, FAILED` when the schema also has `PENDING`, names a `ProcessingStateService`
that was never written, and omits `ConsumerLifecycleController` and `StoreGate` from the package
map.

For a service whose whole justification is supportability, the first three documents anyone opens
are wrong about what it does. This is the cheapest item in this report and the one that most affects
whoever touches the repo next.

### 🟠 Comment volume has passed the point of being an asset ✓

**56% of `src/main/java` is comment or blank** — 13,249 lines total, 5,820 lines of code.
Worst ratios: `pipeline/Json.java` 262 lines for 64 of code; `application/NowSubscriptionsSource.java`
60 lines of prose for one method signature; `pipeline/HearingDates.java` 450 for 153.

The parity commentary is genuinely load-bearing and worth keeping. Four categories are not:

1. **Comments that contradict the code.** `application/HearingPayloadSource.java:10-12` and
   `application/RegisterSubmissionClient.java:9-14` both still say "a logging stub in this
   increment"; the latter adds that the happy path "invokes it **zero** times". `OrderedDates`
   explains why a collapse must not happen ten lines above the code that performs it.
   `config/PipelineConfig.java:28-29` claims every bean is declared as its port type; three of six
   are not.
2. **Comments that restate the code.** A 17-line javadoc on `lostContentionRace`, whose entire body
   is one `return`.
3. **Copy-pasted justification.** The identical three-line `PMD.OnlyOneReturn` paragraph appears in
   **17 files** — one `<exclude name="OnlyOneReturn"/>` in the ruleset deletes all 51 lines.
4. **Design documentation living in code.** 43 lines before `DistributionPipeline`;
   `application.yaml` is roughly 60% prose. These are `TECHNICAL_DESIGN.md` sections that drift the
   moment the design does — as category 1 shows they already have.

Checkstyle actively encourages this: `MissingJavadocType` / `MissingJavadocMethod` are on and
`LineLength` is relaxed to 200 (`config/checkstyle/google_checks.xml:59-61`).

### 🟠 Ports-and-adapters leaks, and duplicated adapters that have already diverged ○

- `application/DistributionPipeline.java:10` and `application/IdempotencyGuard.java:8` import
  `config.ProcessingMetrics`, a Micrometer-backed component; `IdempotencyGuard.java:19` imports the
  concrete `persistence.ProcessedRequestRepository`. The core cannot be compiled or reasoned about
  without `config/` and `persistence/`. Two interfaces owned by `application/` would fix it.
- The legacy `AxiosRetryWrapper` is ported **twice** — `ResultsQueryHearingPayloadClient` and
  `ReferenceDataNowSubscriptionsClient` — with the same constants, loop and pause, ~60 duplicated
  lines. They already differ where it matters: the refdata client guards with
  `.onStatus(status -> !status.is2xxSuccessful(), …)` and a five-line justification that a 304 must
  not be read as an answer; the payload client has no such guard, so a 304 there reads as a cache
  miss.
- `informantregister.results.headers` is documented as the escape hatch for "whatever the mesh turns
  out to need, without a code change". It reaches the command POST and the reference-data GET, but
  `config/LivePayloadConfig.java:108-117` constructs `ResultsQueryHearingPayloadClient` without it —
  the class has no header field at all. If the mesh requires an auth header, every cold-cache read
  403s, reads as a cache miss, and the request dead-letters after five deliveries.
- Two configuration-validation authorities disagree: `PropertiesValidator.java:305-312` requires
  `results.system-user-id` only when payload mode is LIVE; `ResultsCommandGateway.java:110-116`
  requires it always, and that bean is built unconditionally. The documented local STUB run cannot
  start without it, which is why `ServiceTestSupport.SYSTEM_USER_ID` exists purely to paper over it.

### 🟡 Two quality gates that do not gate ○

- **PMD** is skipped by `gradle/pmd.gradle:12-14`'s `onlyIf { gradle.startParameter.taskNames.contains(name) }`
  — a literal string match. `./gradlew check`, `build` and even `:pmdMain` all skip PMD silently and
  go green. The gate survives only because CI happens to spell the task name exactly
  (`ci-build-publish.yml:148`).
- **JaCoCo** excludes `config/**` on the stated grounds of "no meaningful branching" — 944 of 5,820
  code lines, including `PropertiesValidator` (~20 refusal branches) and `ServiceBusHealthIndicator`.
- `checkstyleTest` and `pmdTest` are both disabled, so 29k lines of test code get no static analysis.

### 🟡 Dependabot breaks CodeQL on every action bump ✓

`.github/dependabot.yml:14-22` — the `gradle` ecosystem groups all updates into one PR; the
`github-actions` ecosystem has **no** `groups` block. So `codeql-action/init` is bumped alone while
the sibling `codeql-action/analyze` is left behind, producing
`Loaded a configuration file for version '4.37.7', but running version '4.37.6'` — the current
failure on the two most recent CodeQL runs. One-line fix.

### 🟡 Test-suite shape ○

Full `./gradlew test` is ~4m11s wall, 242s of execution across 94 classes — **95% container-backed
e2e**. Ten IT classes account for ~229s; every unit test plus the entire 386-case parity harness
accounts for the remaining ~13s. The dominant cost is Spring context boot per IT class, not the
containers, which are shared.

- `e2e/ReadinessPolicyIT.java` (423 lines, 34.7s) has roughly half its scenarios constructing a
  plain `ServiceBusHealthIndicator` with an adjustable clock — no container needed — inside a full
  `@SpringBootTest`, duplicating `config/ServiceBusHealthIndicatorTest`.
- `e2e/TraceabilityIT.java:118-156` asserts on exact log prose (`"Hearing payload obtained."`), so
  rewording a log line breaks a container-backed suite. `e2e/FailureSignalIT.java:162,195` does it
  correctly, asserting on `ReasonCode` constants.
- `pipeline/RegisterTransformationChain.java:111-113` pairs two lists positionally
  (`fragments.get(index)` with `addressed.get(index)`), correct only because `SubscriptionMatcher`
  happens to preserve size and order. Any future filter there mis-pairs an authority's fragment with
  another authority's recipients, silently.

---

## 6. What is genuinely strong

- **The persistence layer is the best part of the codebase.** Hand-written SQL, affected-row-count
  as the decision everywhere, all timestamps from the database clock, conditional upsert instead of
  read-then-write, and CHECK constraints that encode the state machine.
- **The golden-parity harness is exactly the right shape for a lift-and-shift** — 386 recorded runs
  of the real Node chain, driven as a parameterised in-JVM test, costing about a second.
- **Settlement discipline in the listener is airtight.** Every path yields a `GuardDecision` and
  settles exactly once in one `switch`. No return-without-settling on the main paths.
- **MDC discipline is correct** — set as soon as the body yields identifiers, set even on the
  validation-rejection path, never invented when absent, cleared in a `finally`.
- **No PII above DEBUG anywhere**, across 70+ log statements. No `System.out`, no
  `printStackTrace`, no empty catch, no success returned from a catch block.
- **ASB health is correctly kept out of readiness**, and its staleness rule is unusually well
  reasoned — an idle queue at 4am does not read as an outage, but a consumer never answered does.
- **The replay contract is enforced by the guard, not by operator memory.** A wrong replay fails
  safely and visibly rather than duplicating.
- **Method size and cohesion are not a problem.** The longest real method in main is
  `SubscriptionRules.match()` at 49 lines. The size complaint is entirely at file level and entirely
  comments.
- **Commit discipline is real.** 232 commits, with `test:` commits genuinely preceding their `feat:`
  counterparts — the TDD rule is being followed, not just written down.

---

## 7. Suggested order of work

1. **Consult the delivery budget on admission paths.** One condition in `DistributionPipeline.process`
   closes the blocker that silently parks messages with no `FAILED` row — the exact failure the
   rewrite exists to eliminate.
2. **Move the deadline check inside the submission loop**, and add a `PropertiesValidator` rule that
   budgets the submission leg. Until this lands, duplicate register rows are a load-dependent
   certainty rather than a crash-window rarity.
3. **Two lines of actuator config** — `show-details` and `loggers`. Nothing else on this list gives
   support so much for so little.
4. **Guard the `ObjectNode` cast** and classify it non-transient, with the shape cases as tests.
5. **Fix the two date defects** (`OrderedDates` null collapse, `asDayMonthYear` pivot) and add corpus
   cases for both — these put wrong data on the wire, which is worse than failing.
6. **Set `maxHeapSize = '2g'` on the test task** and reconsider `failFast`, so the documented build
   command completes and a crashed run stops wedging the next one.
7. **Reconcile README, `.claude/rules/` and `tasks.md` with what shipped**, and delete the "later
   story" comments from the port interfaces. Cheapest item here, biggest effect on whoever touches
   the repo next.
8. **Add submission and payload metrics**, split `PIPELINE_TRANSIENT_FAILURE`, and write the runbook.
   Then the service can be operated by someone who did not build it.

---

*Audit of `service-cp-crime-informant-register` @ `b3ba369`, compared against
`cpp-context-azure-legalaidagency/azure-functions/durable-functions`. Findings marked ✓ were
confirmed against the cited source; findings marked ○ are leads to verify.*
