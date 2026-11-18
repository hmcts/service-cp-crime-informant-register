# Changelog — service-cp-crime-informant-register

All notable changes to this service are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Change tracking starts at go-live.** Everything before that — the build-out of the service and the
port of the function-app pipeline — is recorded in the git history and on the
[Informant Register Service](https://tools.hmcts.net/confluence/spaces/CRA/pages/2004096218/Informant+Register+Service)
Confluence page, which is the source of truth for design. Pre-go-live churn is deliberately not
replayed here.

Entries land under `[Unreleased]` as they are merged and move into a version section at release.
Anything that changes register **content** must also have an entry in
[DEVIATIONS.md](DEVIATIONS.md) with its own assertion in the parity harness.

## [Unreleased]

### Added
- 2026-09-08 — **Every line about an outbound command names the request and hearing it belongs to.**
  `ResultsCommandGateway` named the authority and nothing else, and an authority id alone is not a
  lead: a hearing produces one command per prosecuting authority, so "Results refused the command.
  authority=…" was five near-identical lines in a run with none of them saying which hearing lost
  its register. A `CommandCorrelation` (`source`, `requestId`, `hearingId`, `authorityId`) is now
  passed down from `ResultsRegisterSubmissionClient` and rendered on all seven of the gateway's
  lines, the stray `Retry-After` warning included. The identifiers already reached the log index
  through the delivery's MDC — the run is one thread from the broker callback down, a sequential
  loop over the authorities and a blocking `RestClient` — but that is a property of how the pipeline
  submits today, not of the adapter: parallelising the per-authority POSTs is the obvious
  optimisation and would have stripped the MDC from every line in the package with no test failing.
  `hearingId` also joins the submission client's own lines, which named only the request, because
  "was this hearing's register filed?" is the question support arrives with; it is read from the
  document, which is the register of that hearing. The receipt line likewise names `requestId`,
  `hearingId` and `hearingDay` in its text — the house convention `DistributionPipeline`,
  `IdempotencyGuard` and the results adapters already follow — so the line an investigation starts
  from is legible pasted into a ticket or read in a terminal, not only through the structured fields.
- 2026-09-08 — **The delivery receipt line answers the questions second and third line actually
  arrive with.** It carried `source`, `eventType` and `finalPermittedDelivery`; it now also carries
  the broker's `enqueuedTime` and `lockedUntil`, the message's own `sharedTime`, and
  `attributedTo=message-user|system-identity`. Read against the line's own timestamp, `enqueuedTime`
  is the queue dwell — the difference between "the hearing was resulted late" and "this service is
  behind" — and `sharedTime` is the producer-side half of the same question; `lockedUntil` is what a
  lock-lost report (FR-016) gets read against, since a run that settled after it overran and lock
  renewal is not covering the pipeline. The two stamps come off two clocks, so the dwell is
  indicative and no code compares them. `attributedTo` names *which* identity a run's outbound calls
  went out as, which is the only field separating a producer that named no user from the documented
  system-identity fallback; it is produced by `CallerIdentity#label()` beside the `orSystem(...)`
  that fills `CJSCPPUID`, so one resolution feeds both and a future rule cannot make the line lie.
  The user id itself is still never written at any level. Absent broker stamps render as `none`
  rather than the literal `null`, which would read as a defect in this service.
- 2026-09-08 — **Every line of a delivery is joinable to the queue.** `sequenceNumber` joins
  `deliveryCount` in the MDC, so a log line can be tied to the message in Service Bus Explorer or a
  management-API listing. `messageId` would be the obvious handle and is inadmissible — it is text
  the producer chose — whereas the sequence number is broker-assigned and about nobody. It is put in
  place before the body is read, which makes it the *only* handle on the lines written when the
  processed log is unreachable and the delivery is returned unexamined: "is the same message coming
  back, and how much of its budget has the outage eaten" is exactly what an outage raises.

### Fixed
- 2026-11-18 — **Four settlement and re-park gaps closed in review of the sequence-number replay
  feature.** A delivery whose body could not even be retrieved from the broker context escaped the
  settlement boundary entirely — `context.getMessage()` ran ahead of `outcomeOf`'s catch, so a
  failure there left the message locked with no settlement attempt. It is now read inside the same
  boundary as every other broker fact. A delivery missing its `messageId` or lock token was admitted
  into processing and could reach the FAILED-state write, which requires `messageId` non-null;
  `captureDelivery` now validates both up front and abandons the delivery unconditionally if either
  is absent, the same outcome as any other capture failure. A re-park whose stored `failure_reason`
  is missing or unrecognised was reported under whatever `parking_category` happened to be recorded
  — `non-transient` included — rather than FR-009's required `exhausted / UNEXPECTED_FAILURE`; the
  recorded category is now only trusted once the reason itself is known good, and the earlier receipt
  log line that names the prior failure reason no longer emits it raw when it does not resolve to a
  declared `ReasonCode`. See `specs/001-replay-by-sequence-number/spec.md` (FR-009, FR-012) and its
  "Rolling deployment" edge case, which now also documents the one gap left un-mitigated in code: an
  older consumer re-parking a row a newer consumer already parked leaves `parking_sequence_number`/
  `parking_category` stale rather than clearing them, which the rollout for this migration must
  avoid by ensuring no older consumer remains able to re-park rows once any pod runs this version.
- 2026-11-18 — **A parked request can now be recovered with the Azure portal's dead-letter
  "Re-send", not just bespoke tooling.** The guard told a deliberate resubmission apart from the
  delivery that exhausted the retries by comparing `messageId` — and the portal's "Re-send" copies
  the message as it stands, `messageId` included, so every real resubmission was read as a
  redelivery and re-parked, reporting the generic `DELIVERY_LIMIT_EXHAUSTED` instead of the
  request's real failure. The decision now rests on the broker's own **sequence number**, which a
  genuinely accepted re-send always changes and a redelivery never does; a re-park reports the
  request's own recorded category and reason; and rows parked before this change recover via a
  documented two-condition heuristic (`informantregister.replay.legacy-margin`, default 5 minutes).
  See `specs/001-replay-by-sequence-number/spec.md`. Not a parity deviation — this is "transport
  recovery," the one area the constitution already sanctions for behavioural change; `migration
  V2` is additive and existing rows stay valid.
- 2026-11-02 — **`hearingStartTime` is a full date-time again, with London's true offset.** The
  court session's start had been sent as a time of day only (`12:00:00+01:00`), which removed the
  only hearing date the CPPI API (`results.prosecutor-results`) returns from the stored
  `add-informant-register` payload and would break consumers integrated against the legacy shape
  (`2026-10-23T12:00:00Z`). It is now the legacy's London date-time with the real offset in place
  of the literal `Z`: `2026-10-23T12:00:00+01:00` in BST, `2026-10-26T10:00:00Z` in GMT. Amends
  [DEVIATIONS.md](DEVIATIONS.md) entry 15.
- 2026-09-08 — **The last three hand-backs no longer end as broker-reasoned dead-letters.** Service
  Bus makes an abandoned message available again immediately with no back-off, so a hand-back that
  keeps recurring spends the whole delivery budget back-to-back and the broker parks the message
  under *its own* reason — no reason code of ours, no `deadlettered` reading, nothing in the log
  index to search for. `IdempotencyGuard.admit` already escalated its three admission paths; these
  three could not reach it. `STALE_RUNNER` comes from the outcome writes, so the delivery's budget
  position now travels on `RunClaim` and the single rejection all four writes fall back to parks the
  message with `STALE_RUNNER` as the detail when the budget ends here — which under
  `recordExhaustion` is every time, that method being reached only on the final delivery.
  `STORE_UNAVAILABLE` and `UNEXPECTED_FAILURE` are manufactured in the listener and are escalated
  there, through one helper both routes pass through so a third inherits the rule. The path's own
  reason code is carried through rather than replaced by `DELIVERY_LIMIT_EXHAUSTED`: the budget says
  when a request was parked, never why. Nothing is written on any of the three — none of them holds
  the claim, and on the store path the log is unreachable and the body was never read at all — so
  what the budget buys is attribution, not state. Intake still stops on the store path.
- 2026-09-08 — **A delivery the broker's own accessors cannot describe is still settled.** The
  correlation reads and the store-availability precondition sat in `onMessage`, which has a
  `finally` and no `catch`, ahead of the catch-and-settle boundary. Neither accessor is safe:
  `getDeliveryCount()` unboxes a `Long` an AMQP header need not carry and `getSequenceNumber()`
  casts an annotation whose type it does not check, so a message stamped unusually — a hand-built
  republish, a dead-letter resubmission — threw while being *described* rather than while being
  processed. The throw escaped past settlement with auto-complete disabled: lock left to expiry,
  five redeliveries into the same failure, and the broker parking it under its own reason with no
  `processed_request` row and no reason from this service — the silent loss the design exists to
  prevent, and the same defect the body read was already moved inside the boundary to avoid. All
  three reads now sit inside it, so an undescribable delivery is handed back, reported and counted
  like any other unanticipated fault; a probe that throws instead of answering is treated as the
  store outage it is, and stops intake rather than escaping. Proven red before green
  (`DeliveryReceiptLogTest.UndescribableDelivery`).
