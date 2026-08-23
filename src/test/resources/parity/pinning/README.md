# Oddity pinning pack

Twenty-six entries, one per defect or sanctioned oddity in the informant-register
port. Nineteen are backed by a recorded run of the **real Node function app**;
seven are outside the oracle's reach and say so.

**These tests exist so that ported behaviour cannot be silently "fixed".**

Every entry names a behaviour that looks like a bug, cites the Node code that
produces it, records what the Node code actually does, and states the exact
assertion a JUnit pinning test must make. The assertions are written from one
angle throughout: *what would a competent Java developer naturally write instead,
and would this test catch them?* Several of these behaviours are genuinely
wrong. Correcting them is allowed. Correcting them **quietly** is not.

The rule the pack enforces:

> A pinned behaviour changes only in a commit that also adds the corresponding
> row to `service-cp-crime-informant-register/doc/DEVIATIONS.md`.

---

## Layout

```
pinning/
├── entries.js          the catalogue: evidence, status, assertion, input construction
├── build-pinning.js    writes the inputs and drives ../oracle/run-transform.js
├── manifest.json       the index — read this first
└── <entry-id>/
    ├── inputs/hearing.json          the payload handed to SetInformantRegister
    ├── inputs/subscriptions.json    the refdata now-subscriptions body (absent when
    │                                the case makes the refdata call fail)
    ├── inputs/params.json           { sharedTime, cjscppuid, refdata?, clockPinIso }
    ├── expected.json                the outbound document array; JSON null when none
    └── meta.json                    evidence, edit audit trail, what the oracle observed
```

`expected.json` is the array `ProcessOutboundInformantRegister` receives
(`InformantRegisterOrchestrator/index.js:47`) — element *i* is the body of the
*i*-th `add-informant-register` POST.

## Running

```bash
node pinning/build-pinning.js            # rebuild everything (~30 s)
node pinning/build-pinning.js --only d04

# replay one case by hand, exactly as recorded
node oracle/run-transform.js --case pinning/d04-subscription-matched-on-major-creditor-code
```

Nothing here re-implements any transformation. `entries.js` only edits **input**
JSON; every `expected.json` comes from running the real activities out of
`cpp-context-azure-legalaidagency/azure-functions/durable-functions`. Verified
with Node v24.18.1 and the function app's own `node_modules`.

## Properties that are checked, not assumed

An earlier version of this file claimed all three properties below in one
sentence, and `build-pinning.js` only ran the second of them. Each is now a
separate check with its own command, and each says what it does and does not
establish.

* **Byte-reproducible replay** — every one of the 19 recorded cases replays
  byte-identically through `oracle/run-transform.js --case`.

* **Deterministic at a fixed clock** — `build-pinning.js` runs each case **twice**
  at `clockPinIso` and compares the document arrays. **19/19 identical**, recorded
  per case as `oracle.deterministic` and in aggregate under
  `manifest.json → checkedProperties`. A non-deterministic case prints
  `*** NON-DETERMINISTIC AT A FIXED CLOCK ***` and cannot be pinned, because a
  pinning expectation is asserted literally.

* **Clock-independent** — every case is re-run at `2026-12-04T23:40:00.000Z`, on
  the far side of both a London date boundary *and* the BST/GMT boundary. **19/19
  unchanged**, each `meta.json` recording `oracle.clockDependent: false`. (Nine
  cases in the sibling `recorded/` corpus *are* clock-dependent; none of them is a
  pin.)

* **Timezone-independent** — this needs a flag to mean anything, and the earlier
  claim of "under `TZ=Asia/Tokyo`" did not use it. `oracle/lib/stubs.js` overwrites
  `TZ` with `Europe/London`, so a plain `TZ=Asia/Tokyo` run tests that overwrite,
  not the transform. The pin is now skippable:

  ```bash
  TZ=Asia/Tokyo          IR_ORACLE_NO_TZ_PIN=1 node pinning/build-pinning.js
  TZ=America/Los_Angeles IR_ORACLE_NO_TZ_PIN=1 node pinning/build-pinning.js
  ```

  Both were run on 2026-08-23 and every `expected.json` across `pinning/` and
  `recorded/` hashed identically to the London build.

* **Digests you can check with `sha256sum`** — `meta.json → digests` hashes the
  **bytes of the written file**, so `sha256sum pinning/<id>/expected.json`
  reproduces `digests.expected` exactly. It previously hashed compact
  `JSON.stringify` output, which matched nothing a reviewer could run and differed
  silently from the convention `recorded/` uses. Both directories now hash file
  bytes.

* **Provenance** — every hearing payload starts as a byte-identical copy of a
  fixture from the function app's own Jest suite, and every subscription starts
  as a verbatim copy of a real captured reference-data entry. `meta.json`
  `provenance.edits` lists every change in plain English, and
  `meta.json → sourcePinning` plus `manifest.json → sourcePinning` name the Node
  commit (`a8d3c00b92c3d4cc5da5a555699a63d30adcf8db`, clean tree), the
  `package-lock.json` digest, the Node version and the oracle's own digest — so
  "which code produced this pin?" has an answer that is not just a file path.

## Statuses

| Status | Count | Meaning |
|---|---|---|
| `DEMONSTRATED` | 19 | an oracle run is recorded; assert against `expected.json` |
| `BLOCKED` | 7 | the behaviour is outside the oracle's reach; `blockedReason` says why, and the assertion is written for the test level that *can* reach it |

The blocked seven are not gaps in rigour — they are the delivery leg (D1 POST,
D2 orchestrator, D16 trigger dedupe), the payload-source leg (D13, D14), a
non-behaviour (D15 committed secrets) and an observability waiver (DEVIATIONS #3).
The oracle deliberately covers the transform only.

## Cross-reference

| Register | Entries |
|---|---|
| Design doc §8.1 defect register D1–D17 | `d01`…`d17` (D12 → `d12` + `o03`; D17 → `d17`) |
| Design doc §2.2 behaviour-lock list | `s01`, `s02`, `s04`, `s05`, `s06` |
| `design_rules.md` sanctioned oddities | `o01` group proceedings not skipped · `o02` 2-arg vocabulary call · `d12` groupId from group masters only · `d17` letter delivery ignored |
| `doc/DEVIATIONS.md` #1 / #2 / #3 | `d14` / `d01`+`d02` / `v03` |

## Two things this pack found that the register does not name

1. **`s06`** — `DefendantContextBaseService.js:294-297` runs the same broken
   date parse as D10 with **no** error handling, and runs *earlier*. Whether a
   bad `orderedDate` surfaces as D10's `TypeError` (from the broken catch block)
   or as a bare `Error: Invalid date format` depends only on how many judicial
   results a defendant happens to have. Guarding one call site leaves the other
   broken, so both are pinned.
2. **`s05` / `d11`** — the literal string `"Invalid dateZ"` is POSTed into
   fields the frozen contract types as date-times, on **unmodified real
   fixtures**. `d11` shows the same leak reached through the hardcoded
   `DD/MM/YYYY` duration parse: a duration date in ISO form is converted to
   garbage and shipped.

Both feed the open decision already recorded in `../README.md` §5 finding 6:
Node POSTs contract-violating bodies and swallows the response. The Java port
cannot reproduce that and cannot silently drop it. That decision belongs with
the Results team, and until it is made these entries exist to stop anyone
choosing for them.

## What is NOT pinned, and why

* **D7 as written in the register.** The `TypeError` at
  `InformantRegisterSubscriptions/index.js:18` is unreachable through the
  orchestrator — `SetInformantRegister/index.js:83` always assigns a non-empty
  String. Proved rather than assumed; `d07` pins the reachable twin
  (`ReferenceDataService.js:38`) and `meta.json` says so.
* **`matchCpsProsecuted`** (`SubscriptionsService.js:56-59`) is dead code. No
  input can reach it, so no case can pin it. `d05` records the obligation not to
  port it as a reviewer note.
* **`defendantAttendance`, `legalEntityDefendant`, `applySubscriptionRules: true`
  against real data.** UNSPECIFIED — see `oracle/README.md` §6. `o02` probes the
  vocabulary gate with a *constructed* subscription and says so; it is evidence
  about reachable code, not about production data.
* **The inbound-`null` propagation** (`../README.md` §5 finding 6). Deliberately
  not pinned: pinning it would presume the answer to a decision that is open
  with the Results team.
* **`s03` does not exist.** The `fileName` format
  (`InformantRegister_{code}_{localDate(registerDate)}.csv`,
  `OutboundInformantRegister/index.js:48`) was going to be its own entry; it is
  asserted inside `d09` (the BST date it is built from) and `s02` (the
  first-wins authority code it is built from), which is where it actually
  matters. The gap in the numbering is deliberate, not a missing case.
* **`courtRoom: 'N/A'` for box and SJP hearings** (`CourtSessionMapper.js:15`).
  Not pinned here — SJP is out of scope for this service, and the sibling
  `recorded/` corpus already carries the box-hearing shape. Add an entry if the
  SJP leg is ever brought in.
