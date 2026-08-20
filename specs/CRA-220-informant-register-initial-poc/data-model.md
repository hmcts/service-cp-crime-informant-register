# Data Model — CRA-220 walking skeleton

Phase 1 output. Authoritative narrative: `doc/TECHNICAL_DESIGN.md` (state machine + idempotency);
this file is the feature-scoped distillation the tasks and migrations are written from.

## Entities

### DistributionCommand (domain record — not persisted)

The validated form of the inbound queue message. Typed Java record; parsing rejects unknown fields
(closed contract) and enforces formats before the state machine starts.

| Field | Type | Validation |
|-------|------|------------|
| `source` | String | required; enum `RESULTS` |
| `requestId` | UUID | required; RFC 4122 format |
| `hearingId` | UUID | required; RFC 4122 format |
| `hearingDay` | LocalDate | required; ISO `yyyy-MM-dd` |
| `sharedTime` | Instant | required; ISO-8601 date-time |
| `eventType` | String | required; enum `Hearing_Resulted` |

Canonical schema: `src/main/resources/contracts/distribution-command.schema.json` (draft-07,
`additionalProperties: false`). Contract tests assert parser ↔ schema agreement.

### processed_request (table — Flyway V1)

One row per `(source, request_id)`. The durable memory that makes the service idempotent.

| Column | Type | Notes |
|--------|------|-------|
| `source` | text | PK part 1; not null |
| `request_id` | uuid | PK part 2; not null |
| `hearing_id` | uuid | not null — support querying |
| `hearing_day` | date | not null |
| `shared_time` | timestamptz | not null |
| `event_type` | text | not null |
| `request_fingerprint` | text | not null; SHA-256 hex over the canonical form below; written at insert, never updated; the collision comparison |
| `status` | text | not null; one of `RECEIVED`, `RETRYING`, `COMPLETED`, `FAILED` |
| `attempts` | integer | not null default 0; lifetime count of pipeline-run starts (successes included); incremented atomically in the same statement that acquires the claim; never a control variable |
| `completion_reason` | text | nullable; e.g. `no-authorities` |
| `failure_reason` | text | nullable; sanitised bounded reason code + summary — no PII, no raw exception text |
| `exhausted_message_id` | text | nullable; broker messageId of the delivery that exhausted `maxDeliveryCount`; written in the same transaction as `status = FAILED` |
| `audit_note` | text | nullable; e.g. replay note on `FAILED → RECEIVED` |
| `claim_owner` | text | nullable; runner identity (instance id + delivery id) while a run is in flight |
| `claim_token` | uuid | nullable; minted fresh on **every** claim acquisition; the predicate that lets an outcome write prove the claim it holds is still the current one |
| `claim_expires_at` | timestamptz | nullable; claim liveness — expiry is the crashed-runner recovery path |
| `created_at` | timestamptz | not null, `DEFAULT now()` |
| `updated_at` | timestamptz | not null, `DEFAULT now()`; every update sets it explicitly to `now()` |

`claim_owner`, `claim_token` and `claim_expires_at` are the **claim triple**: they are written
together and cleared together, never independently.

Indexes: PK `(source, request_id)`; secondary on `(hearing_id, hearing_day)` for the support query
"was this hearing processed?".

#### V1 constraints

```sql
CONSTRAINT processed_request_status_chk
    CHECK (status IN ('RECEIVED', 'RETRYING', 'COMPLETED', 'FAILED')),
CONSTRAINT processed_request_attempts_chk
    CHECK (attempts >= 0),
CONSTRAINT processed_request_claim_triple_chk
    CHECK ((claim_owner IS NULL) = (claim_expires_at IS NULL)
       AND (claim_owner IS NULL) = (claim_token IS NULL)),
CONSTRAINT processed_request_exhausted_id_chk
    CHECK (status <> 'FAILED' OR exhausted_message_id IS NOT NULL)
```

Two further rules are enforced by the application because a `CHECK` would be either wrong or
expensive:

- `failure_reason` is mandatory when `status` is `RETRYING` or `FAILED`. It is not a `CHECK`
  because the `FAILED → RECEIVED` replay transition clears it in the same statement that leaves
  the row non-terminal, and a column-level constraint would have to encode that transition.
- `exhausted_message_id` is mandatory **exactly** when `status = 'FAILED'`; the cheap half (must be
  present) is the `CHECK` above, and the other half (must be absent otherwise) is enforced by the
  replay statement clearing it.

**Single time authority**: the database clock. `created_at`/`updated_at` carry `DEFAULT now()` and
every update sets `updated_at = now()` in SQL; claim expiry is computed as `now() + :lease` in SQL
and compared against `now()` in SQL. The application never supplies a timestamp for these columns
and never compares a JVM clock reading against a stored one, so multi-node clock skew cannot affect
a claim decision.

#### Fingerprint canonicalisation

`request_fingerprint` is the lowercase hex encoding of `SHA-256` over the UTF-8 bytes of:

```text
lower(hearingId in canonical RFC 4122 form)
    + "|" + hearingDay as ISO yyyy-MM-dd
    + "|" + sharedTime as Instant.toString() (UTC, ISO-8601, after parse)
    + "|" + eventType verbatim
```

Every component is normalised **after** parsing, not taken from the wire text, so an
uppercase-hex UUID and an offset-bearing instant (`2026-08-20T09:00:00+01:00`) produce
exactly the same fingerprint as their canonical/`Z` equivalents (`2026-08-20T08:00:00Z`). Only a
genuine change to an immutable field is a collision.

### processed_output (table — Flyway V1, **schema only this increment**)

Zero or more per request; one per prosecuting-authority output. **No rows are written in CRA-220**
(the stub pipeline produces no outputs); population, skip-on-replay and `request_digest`
reconciliation semantics arrive with the real submission stories. The schema below is complete from
V1 so those stories add rows, not columns.

| Column | Type | Notes |
|--------|------|-------|
| `output_id` | uuid | PK; not null; generated by the **application** (UUID v4), not a database default |
| `source` | text | not null; FK part 1 |
| `request_id` | uuid | not null; FK part 2 |
| `prosecution_authority_id` | text | not null |
| `status` | text | not null; one of `PENDING`, `POSTED`, `FAILED` |
| `request_digest` | text | **nullable** — the only nullable column; SHA-256 of the outbound body, written once the POST body exists (reconciliation; later story) |
| `created_at` | timestamptz | not null, `DEFAULT now()` |
| `updated_at` | timestamptz | not null, `DEFAULT now()`; updates set it explicitly to `now()` |

`PENDING` is the row-created-before-POST state: the later submission story writes the row before it
calls Results so an ambiguous POST outcome is still recorded. The semantics are a later story; the
schema supports them from V1.

```sql
CONSTRAINT processed_output_status_chk
    CHECK (status IN ('PENDING', 'POSTED', 'FAILED')),
CONSTRAINT processed_output_unique_authority
    UNIQUE (source, request_id, prosecution_authority_id),
CONSTRAINT processed_output_request_fk
    FOREIGN KEY (source, request_id) REFERENCES processed_request (source, request_id)
    ON DELETE RESTRICT
```

`ON DELETE RESTRICT`: the processed log is append-only support evidence. Deleting a request row out
from under its outputs would destroy the record of what was submitted, so the database refuses it
rather than cascading.

## State machine (processed_request.status)

The state machine starts **only after** contract validation succeeds; a contract-invalid delivery
gets no row. Full branch narrative: `doc/TECHNICAL_DESIGN.md` "Processing State Machine".

| From | To | Trigger | Persistence rule |
|------|----|---------|------------------|
| (none) | RECEIVED | first validated delivery of a new `(source, request_id)` | `INSERT … ON CONFLICT DO NOTHING` — row, claim triple and `attempts = 1` in one statement |
| RECEIVED / RETRYING | COMPLETED | run succeeds (empty output set ⇒ `completion_reason = 'no-authorities'`) | conditional on the claim triple; recorded before `complete()` |
| RECEIVED / RETRYING | RETRYING | transient run failure, deliveries of this message remain | conditional on the claim triple; reason recorded before `abandon()` |
| RECEIVED / RETRYING | FAILED | transient failure on the final permitted delivery (broker delivery count = 5) | conditional on the claim triple; `exhausted_message_id` written in the same transaction; before `deadLetter()` |
| FAILED | RECEIVED | delivery for the same key under a **different** messageId than `exhausted_message_id` (deliberate resubmission) | attempts preserved and incremented; `failure_reason` and `exhausted_message_id` cleared; `audit_note` written; claim taken |
| FAILED | FAILED (no change) | delivery under the **same** messageId | no run; `deadLetter()` re-attempted |
| COMPLETED | — | terminal; any delivery acknowledged without a run | row untouched |

Guard decisions that are not transitions:

- **Idempotency collision**: existing row, `request_fingerprint` differs → dead-letter with reason;
  row untouched; no run (spec FR-018).
- **Contested delivery**: non-terminal row, unexpired claim owned by another runner → `abandon()`;
  never `complete()`; no run (spec FR-008).
- **Stale claim reclaim**: non-terminal row, claim absent/expired → conditional-update reclaim
  (exactly one racing delivery wins) → run.
- **Stale-runner rejection**: an outcome write whose claim-triple predicate matches zero rows — the
  claim was reclaimed while this runner was working. The result is discarded, a WARN log and the
  `informantregister_stale_runner_rejections_total` counter are emitted, and the delivery is
  abandoned (spec FR-008).
- **Store unavailable**: checked before validation; `abandon()` + suspend intake; no row change
  (spec FR-015).

## Guard operations

Exact statements, so nothing is invented at implementation time. `:owner` is this runner's identity,
`:token` a freshly minted UUID, `:lease` the `informantregister.claim.lease` interval. Every
statement's **affected-row count is the decision**; zero rows never triggers a re-read loop.

### 1. New request (claim acquisition and first attempt in one statement)

```sql
INSERT INTO processed_request (
    source, request_id, hearing_id, hearing_day, shared_time, event_type,
    request_fingerprint, status, attempts,
    claim_owner, claim_token, claim_expires_at,
    created_at, updated_at)
VALUES (
    :source, :requestId, :hearingId, :hearingDay, :sharedTime, :eventType,
    :fingerprint, 'RECEIVED', 1,
    :owner, :token, now() + :lease,
    now(), now())
ON CONFLICT (source, request_id) DO NOTHING;
```

1 row → this delivery owns a fresh request; run the pipeline. 0 rows → a record already exists (this
delivery lost the insert race, or the request is simply known); fall through to the read-and-branch
path below. The claim and the first `attempts` increment are part of this one statement, so a
crashed runner can never leave a row with a claim but no attempt recorded.

### 2. Read and branch

```sql
SELECT status, request_fingerprint, exhausted_message_id,
       attempts, claim_owner, claim_expires_at
  FROM processed_request
 WHERE source = :source AND request_id = :requestId;
```

Branch per the transition table: fingerprint mismatch → collision (statement 6); `COMPLETED` →
`complete()`, no run; `FAILED` → compare the arriving broker messageId with the row's
`exhausted_message_id`: **equal** → no run, re-attempt `deadLetter()`; **different** → replay via
statement 5; `RECEIVED`/`RETRYING` → statement 3.

**No spin rule**: after any conditional claim `UPDATE` that affects 0 rows, the delivery is
`abandon()`ed immediately. The guard does **not** re-read and retry in a loop — broker redelivery is
the retry mechanism, and it already carries back-off and a delivery budget. A loop would burn CPU
holding a lock and could starve the winning runner.

### 3. Reclaim a stale claim (non-terminal record)

```sql
UPDATE processed_request
   SET claim_owner = :owner,
       claim_token = :token,
       claim_expires_at = now() + :lease,
       attempts = attempts + 1,
       updated_at = now()
 WHERE source = :source
   AND request_id = :requestId
   AND status IN ('RECEIVED', 'RETRYING')
   AND (claim_expires_at IS NULL OR claim_expires_at < now());
```

**Status stays whatever it was** (`RECEIVED` or `RETRYING`) — the reclaim moves the claim and the
attempt counter, not the state; the state changes only when the run produces an outcome. 1 row →
this delivery owns the run. 0 rows → the claim is live, or another delivery won the race, or the row
turned terminal in between; `abandon()` (no spin).

### 4. Outcome writes (all predicated on owner **and** token)

```sql
-- COMPLETED
UPDATE processed_request
   SET status = 'COMPLETED', completion_reason = :reason,
       claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL,
       updated_at = now()
 WHERE source = :source AND request_id = :requestId
   AND claim_owner = :owner AND claim_token = :token;

-- RETRYING
UPDATE processed_request
   SET status = 'RETRYING', failure_reason = :reasonCode,
       claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL,
       updated_at = now()
 WHERE source = :source AND request_id = :requestId
   AND claim_owner = :owner AND claim_token = :token;

-- FAILED (same transaction as the settlement decision)
UPDATE processed_request
   SET status = 'FAILED', failure_reason = :reasonCode,
       exhausted_message_id = :messageId,
       claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL,
       updated_at = now()
 WHERE source = :source AND request_id = :requestId
   AND claim_owner = :owner AND claim_token = :token;
```

1 row → the outcome is durable; settle the message. **0 rows → the claim was reclaimed while this
runner was working**: the runner discards its result, logs WARN with the stale-runner counter, and
abandons the delivery. Broker redelivery re-enters the state machine from statement 2. There is no
separate claim-release statement: a runner that exceeds its processing deadline aborts by writing
the RETRYING outcome (above) — which clears the claim under the same owner+token predicate — and
abandoning the delivery.

### 5. FAILED replay (fresh message identity)

```sql
UPDATE processed_request
   SET status = 'RECEIVED',
       claim_owner = :owner, claim_token = :token, claim_expires_at = now() + :lease,
       attempts = attempts + 1,
       failure_reason = NULL,
       exhausted_message_id = NULL,
       audit_note = :note,
       updated_at = now()
 WHERE source = :source
   AND request_id = :requestId
   AND status = 'FAILED'
   AND exhausted_message_id <> :messageId;
```

- `attempts = attempts + 1` — **preserved, not reset**: the counter is a lifetime tally, so five
  failed deliveries followed by a successful replay leave the row showing 6 (spec FR-004, SC-003).
- `failure_reason` and `exhausted_message_id` are **cleared** on this transition — the record is
  non-terminal again, and the `exhausted_message_id` CHECK only binds `FAILED` rows.
- `audit_note` records the prior failure summary (bounded reason code) and the replay timestamp, so
  the reason the record was FAILED survives the clearing.
- The same-identity redelivery case (arriving messageId **equals** `exhausted_message_id` → no run,
  re-attempt `deadLetter()`, spec FR-007) is decided on the **read** in statement 2 — this statement
  is only reached for a fresh identity. The `exhausted_message_id <> :messageId` predicate is
  defence-in-depth, safe against NULL because the `FAILED` CHECK guarantees the column is populated
  on every `FAILED` row.
- **0 rows here does NOT mean same-identity**: it means the row changed between the read and this
  update — a concurrent replay won the race, or the status is no longer `FAILED`. Per the no-spin
  rule the delivery is abandoned; broker redelivery re-enters at statement 2 and takes whichever
  branch the row's new state dictates.

### 6. Collision check

The fingerprint comparison happens on the **read** (statement 2). A mismatch performs **no write at
all** — the existing row is never touched — and the delivery is dead-lettered with a bounded
collision reason (spec FR-018).

### Why `claim_owner = :owner` alone is never a claim-acquisition condition

Acquisition happens in exactly three places — insert-new (1), reclaim-stale (3), replay (5) — and
each increments `attempts`. A fourth "re-acquire my own claim" path predicated on
`claim_owner = :owner` would let the same runner increment `attempts` twice for one pipeline run,
breaking invariant 2 and the SC-003 arithmetic. Owner is therefore only ever a **release/outcome**
predicate (statement 4), always paired with the token.

## Invariants

1. At most one pipeline run in flight per `(source, request_id)` — enforced by the claim.
2. `attempts` increments exactly once per run start, in the same statement that acquires the claim;
   retry exhaustion is judged solely by the broker delivery count of the current message.
3. State is persisted before the message is settled; a settlement failure after persistence follows
   spec FR-016 (COMPLETED → later redelivery acknowledged without work; FAILED → re-attempt
   dead-letter).
4. `request_fingerprint`, `created_at` and the immutable request fields are written once and never
   updated.
5. No PII in any column that reaches logs or metrics (`failure_reason`, `audit_note` are bounded
   reason codes plus a sanitised summary — never raw exception text or message-body fragments).
6. The claim triple is all-or-nothing: `claim_owner`, `claim_token` and `claim_expires_at` are
   always all set or all null (enforced by CHECK).
7. `claim_token` is fresh on every acquisition; no outcome may be written except under the token
   that acquired the claim it settles. A runner whose token no longer matches has been superseded
   and discards its work.
8. A runner aborts its own run at the processing deadline
   (`informantregister.claim.processing-deadline`), which is strictly shorter than the lease
   (`informantregister.claim.lease`), so a live-but-slow runner cannot still be working when its
   claim becomes reclaimable.
9. All lease and expiry arithmetic uses the database clock (`now()`) as the single time source.
