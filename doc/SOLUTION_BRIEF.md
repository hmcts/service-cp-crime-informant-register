# Solution Brief — service-cp-crime-informant-register

## Overview

| Field        | Value                                                    |
|--------------|----------------------------------------------------------|
| Service name | `service-cp-crime-informant-register`                    |
| Team         | Resulting Assistant                                      |
| Programme    | Crime Common Platform (CPP) — Modern by Default (MbD)    |
| Status       | In build — CRA-220 (initial POC / walking skeleton)      |
| Decision     | **Option 2 lift-and-shift**, agreed 19 Aug 2026 (David Edwards, Laxman Kerai, Sachin Dangui) |
| Design source| `~/moj/analysis/results-distribution/InformantRegister/option2-implementation-page.md` |

## Problem Statement

The informant register — the daily record of hearing outcomes sent to prosecuting authorities — is
produced today by a Node.js Azure Function App triggered by Event Grid. That app has no retries, no
dead-letter queue and no alerting: the final POST to Results bypasses the retry wrapper and swallows
its errors, and the orchestrator's catch-all reports success regardless. A register that fails to
reach Results is simply lost, silently, with nothing for support to see or replay.

It is also outside the platform's modernisation target: unmanaged Node runtime, unverified Redis TLS,
committed keys, and no route onto AKS with the rest of the estate.

## Proposed Solution

Lift and shift the function app into a Spring Boot service on AKS, triggered by a **dedicated Azure
Service Bus queue** rather than Event Grid.

```
TODAY
  hearing resulted → Results ── Event Grid ──▶ informantregister FUNCTION APP (Node.js)
                                                 build register → match subscriptions
                                                 → POST add-informant-register ─▶ Results
  Results: informant_register table → 19:00 scheduled job → CSV → email via GOV.UK Notify

AFTER
  hearing resulted → Results ── NEW: dedicated ASB queue ──▶ informantregister-service (Spring Boot, AKS)
                                                 SAME logic, ported JS → Java
                                                 → POST add-informant-register ─▶ Results   (unchanged)
  Results: informant_register table → 19:00 scheduled job → CSV → email    (completely unchanged)
```

The service reads the same Redis cache, calls the same reference-data and results APIs, produces the
same documents, and POSTs them to the same Results command endpoint. Everything downstream of that
POST is untouched.

**The only intended behaviour change is transport reliability**: retries, a dead-letter queue and
alerting, so a failing hearing is retried and — if it keeps failing — parked visibly instead of
being lost. Register *content* is bug-for-bug parity; known oddities are ported as-is and only fixed
later with business sign-off.

## Scope

**In scope**

- New repo `service-cp-crime-informant-register`, from the crime Spring Boot template, deployed to
  AKS via the standard Flux route.
- Port of the function app's pipeline for regular hearings: payload fetch, register building,
  subscription matching, outbound document mapping, POST to Results.
- A small Results-side change: publish a message to the new queue when a hearing is resulted.
- Transport reliability the function app never had: ASB retries, DLQ, alerting.
- An idempotency guard so at-least-once delivery cannot produce duplicate register rows.

**Out of scope — do not touch**

- **SJP hearings.** The SJP informant-register logic lives inside the NOWs function app, which is not
  being migrated now. SJP registers keep flowing through the NOWs app into the same Results table
  exactly as today, so the daily CSV still contains both. This service handles `Hearing_Resulted`
  only; no NOWs-app change of any kind.
- The Results-side generation leg (19:00 sweep, CSV, File Service, GOV.UK Notify) and the platform
  scheduler job.
- `results.prosecutor-results` and the `informant_register` table/schema.
- Behaviour changes to register content.

## Key Integrations

| System | Direction | Protocol | Purpose |
|--------|-----------|----------|---------|
| Azure Service Bus `informantregister.requests` (+ DLQ) | Inbound | AMQP (peek-lock) | Thin per-hearing command published by Results |
| Redis (hearing cache, `INT_` keys) | Outbound | Redis/TLS | Fetch the full hearing payload (claim check) |
| `cpp-context-results` results-query-api | Outbound | HTTPS | Payload fallback on cache miss |
| Reference data (`now-subscriptions`) | Outbound | HTTPS | Informant-register subscription matching |
| `cpp-context-results` results-command-api | Outbound | HTTPS | `POST add-informant-register`, one per prosecuting authority |
| PostgreSQL (service-owned) | Outbound | JDBC | Processed-log / idempotency + processing state |

## Domain Model

| Entity | Meaning |
|--------|---------|
| `DistributionCommand` | The inbound queue message — `source`, `requestId`, `hearingId`, `hearingDay`, `sharedTime`, `eventType` |
| `ProcessedRequest` | One row per `(source, requestId)`: status, attempts, timestamps, failure reason |
| `ProcessedOutput` | One row per authority within a request: status, response code, request digest |
| `RegisterFragment` | Per-prosecuting-authority view of the hearing after court-extract filtering |
| `InformantRegisterDocument` | The outbound `add-informant-register` body for one authority |

## API Surface

**None.** This service exposes no REST API — actuator health and metrics only. Its inbound contract
is the ASB queue message; its outbound contract is the existing, results-owned
`add-informant-register` command. See `doc/API_CONTRACTS.md`.

## Idempotency

`add-informant-register` is not idempotent: a duplicate POST creates a duplicate register row. (The
19:00 sweep dedupes to the latest row per hearing, so the CSV self-heals — but nothing relies on
that.) Redeliveries are guarded by a small service-owned Postgres processed-log keyed on
`(source, requestId)`, with a per-authority output row so that already-posted authorities are skipped
on redelivery or replay. Publishers mint `requestId` deterministically from
`hearingId | hearingDay | sharedTime`, so a republish of the same share is dedupe-able while a genuine
re-share (legitimate, must be reprocessed) mints a new one.

**What the guarantee actually is.** At-most-once submission in all normal operation, including
redeliveries and replays. Across a crash in the instant between a successful POST and recording it,
the guarantee degrades to at-least-once — the duplicate row is absorbed downstream exactly like a
re-share (the 19:00 sweep dedupes to the latest row per hearing). Strict at-most-once is impossible
without Results-side idempotency, and that is out of scope: `add-informant-register` is a frozen,
results-owned contract.

**Ambiguous-outcome policy.** A POST whose outcome is unknown — a timeout, a dropped connection, a
response never read — is retried. We deliberately prefer a possible duplicate, which is absorbed,
over a possible loss, which is silent and is the failure mode this service exists to end.

## Non-Functional Requirements

| Requirement | Target |
|-------------|--------|
| Delivery guarantee | At-least-once from ASB; at-most-once submission per `(source, requestId, authority)` in all normal operation — see below |
| Throughput | Parity with the function app; `maxConcurrentCalls` 2 initially, raised after golden tests prove statelessness |
| Latency | Not user-facing; a register must be submitted well before the 19:00 CSV sweep |
| Availability | ≥2 replicas after soak; broker unavailability must not restart pods (ASB health excluded from readiness) |
| Recoverability | Every failure retried, then dead-lettered visibly; replay is duplicate-safe by construction |
| Observability | `requestId`/`hearingId` on every log line; processed/failed metrics; alerts on DLQ > 0 and failures sustained 15 minutes |
| Data retention | Processed-log only — hearing payloads are never persisted |

## Risks & Assumptions

- **Parity risk.** The register is externally visible to prosecuting authorities. Mitigation: golden
  files from the ~73 Jest cases plus recorded real hearing payloads, and a reviewed deviations
  register — the harness fails on any unregistered difference.
- **Visible failure is a change for operations.** Failures previously invisible now surface on the
  DLQ. Ops expectation-setting is part of cutover.
- **No parallel running is possible** — both paths write to the same table. Cutover is exclusive.
- Assumes Results publishes reliably to the queue (durable event → publisher, framework retry, no
  fire-and-forget) and that the queue, DLQ, workload identity and Redis access are provisioned by
  Platform.

## Open Items

| # | Item | Owner |
|---|------|-------|
| 1 | ASB queue confirmed with Platform (and any better alternative) | Lax |
| 2 | Results-side queue auth: Vault/JNDI connection string vs managed identity on WildFly | Platform / Results |
| 3 | Service repo / deployment naming | Team |
| 4 | Platform provisioning: queue + DLQ, workload identity, Redis access | Platform tickets |

## Out of Scope (for the POC)

CRA-220 delivers a walking skeleton only: consumer, idempotency guard, and ports with stub adapters
(payload fetch and register submission as logging no-ops), plus actuator and container build. The
Redis adapter, the ported transformation pipeline and the results submission adapter are later
stories.
