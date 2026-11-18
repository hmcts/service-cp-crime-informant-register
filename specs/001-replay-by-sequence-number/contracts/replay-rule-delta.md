# Contract delta: the replay rule (FR-013)

This service has no OpenAPI surface (Principle III) — its one externally-visible "interface" that
this feature changes is the **behavioural contract of the dead-letter replay procedure**,
documented today in `doc/API_CONTRACTS.md` under "Replay rule (the exception to the deterministic
`messageId`)" and in its "Failure behaviour (contractual, tested)" table. Per Principle III and
FR-013, this document (not the production code) is the thing that must change **first**; the
implementation task that edits `doc/API_CONTRACTS.md` must land before — or in the same reviewed
change as — the first production code that relaxes the guard's behaviour, and never after.

This is the exact text delta `doc/API_CONTRACTS.md` needs. It is a delta, not a rewrite: every
paragraph not mentioned here is unchanged.

## 1. "Message properties" table — no change

The table itself (`messageId` = `"{source}:{requestId}"` for normal publishing) is unchanged. A
resubmission's `messageId` is no longer constrained by this service — see §2 — but normal producer
publishing keeps the deterministic identity, and the broker duplicate-detection window still
depends on it.

## 2. "Replay rule" section — relaxed

**Before** (current text, condensed): a support resubmission "MUST be published under a fresh
`messageId`, distinct from any identity previously used for that request, and never the identity
that exhausted the retries," because (a) the broker's duplicate-detection window would otherwise
discard it, and (b) "the service uses the difference in identity to tell a deliberate resubmission
apart from the exhausted message coming round again."

**After**: reason (b) no longer holds — the service tells them apart by **broker sequence number**,
not `messageId` — so the requirement becomes conditional on reason (a) alone:

> **Replay rule (sequence number decides; `messageId` is now optional).** The service identifies
> the delivery that parked a request by the broker's own **sequence number**, assigned on accept,
> not by `messageId`. A support resubmission may therefore re-send the dead-lettered body
> **unchanged, `messageId` included** — which is what the Azure portal's dead-letter "Re-send" does
> — and the service will recognise it as a deliberate resubmission and replay it, provided the
> broker actually accepts and enqueues the re-send as a new message (which is what assigns it a
> fresh sequence number).
>
> **`messageId` still matters to the broker, not to the service.** If the re-send is made within
> the queue's duplicate-detection window of the last accepted send under that same `messageId`, the
> broker silently discards it and the service never sees it at all — this is unchanged and is not
> something the service can see or work around. Outside that window, a same-`messageId` re-send is
> accepted and enqueued normally, exactly like any other send. A fresh `messageId` is therefore
> still useful — it lets a resubmission bypass the duplicate-detection window immediately rather
> than waiting it out — but it is no longer *required* by this service's own guard, and normal
> producer publishing keeps using the deterministic `messageId` regardless.
>
> **For a request parked before this change** (no recorded parking sequence number), the service
> falls back to a heuristic: a delivery is treated as a resubmission only when the broker's
> delivery count for it is 0 **and** its enqueue time is later than the parking time by more than a
> configured margin. A same-`messageId` re-send still satisfies this in the ordinary case; the two
> residual false cases the heuristic accepts are documented in the spec's assumptions and are not
> repeated here.

## 3. "Re-send the body verbatim" section — unchanged in substance

The attribution reasoning (`userId` travels with the body, stripped only as the deliberate escape
hatch) is entirely orthogonal to how the parking delivery is identified and does not change. One
sentence needs a forward reference removed: "with the `messageId` the only thing changed" becomes
"with the `messageId` optionally changed" (it may now be left exactly as it was, or changed to
bypass the duplicate-detection window — either way the body is unchanged).

## 4. "Failure behaviour" table — two rows replaced

| Condition | Was | Now |
|---|---|---|
| `(source, requestId)` already `FAILED`, redelivered under the **same `messageId`** that exhausted the retries | Stays `FAILED`; the pipeline does not run; `deadLetter()` is attempted again. | Row removed as a `messageId`-keyed condition. Replaced by: **`(source, requestId)` already `FAILED`, delivered with the **same broker sequence number** as the one recorded when it was parked** → stays `FAILED`; the pipeline does not run; `deadLetter()` is attempted again, with the request's own recorded category and reason (never a generic exhaustion reason, see below). |
| `(source, requestId)` already `FAILED`, resubmitted with a **fresh `messageId`** | Transitioned `FAILED` → `RECEIVED`... | Replaced by: **`(source, requestId)` already `FAILED`, delivered with a **different broker sequence number** from the one recorded when it was parked — whatever its `messageId`** → transitioned `FAILED` → `RECEIVED` via a compare-and-set against the exact failure generation observed (attempts count and parking sequence number); attempts preserved and incremented once; already-`POSTED` authorities skipped; an audit note recorded. If the compare-and-set is not admitted (the record moved underneath the decision), the delivery is handed back for redelivery rather than retried in a loop. |

New row, for the dead-letter description itself (FR-009, previously undocumented because every
re-park used the same generic reason):

| Condition | Outcome |
|---|---|
| A re-park (either row above) | The dead-letter `reason` is the request's own recorded category (`exhausted` / `non-transient`), and the `description` is the request's own recorded failure reason code — **never** the generic `DELIVERY_LIMIT_EXHAUSTED` as a substitute for the real cause. A request with no recorded category (parked before this change) is categorised by an explicit, reviewed mapping of its stored reason. A missing or unrecognised stored reason dead-letters as `exhausted` / `UNEXPECTED_FAILURE` and never throws. |

## 5. Not part of this contract

- The `add-informant-register` outbound command (§2 of `API_CONTRACTS.md`) is untouched — this
  feature changes nothing about register content or the outbound POST.
- The inbound **JSON body** schema (`distribution-command.schema.json`) is untouched — this feature
  is entirely about broker-level delivery identity and the processed-log's state machine, not about
  the message body's fields.
- `userId` / attribution behaviour (`doc/DEVIATIONS.md` #16) is not changed by this feature; only
  that entry's own description of the replay *procedure* ("changing only the broker `messageId`")
  needs the same "optionally" correction as §3 above, because the procedure it describes no longer
  requires a `messageId` change — the attribution finding it pins is unaffected.
