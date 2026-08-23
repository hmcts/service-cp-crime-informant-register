# Changelog — service-cp-crime-informant-register

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed
- 2026-08-23 — **Downstream calls are attributed to the user who shared the results again.** The
  legacy threads the hearing-resulted envelope's `userId` into the orchestration as `cjscppuid`
  (`InformantRegisterEventGridTrigger/index.js:15`) and gives that one value to all three of a run's
  outward calls (`InformantRegisterOrchestrator/index.js:13,31,46`): the payload read, the
  now-subscriptions read (`ReferenceDataService.js:44`) and the `add-informant-register` POST
  (`ProcessOutboundInformantRegister/index.js:21`). The thin queue message dropped it, so the port
  had been calling under its own name. It no longer does.
  - **Contract.** `userId` is added to `distribution-command.schema.json` as an **optional**
    `uuid`; `additionalProperties` stays `false` and the required set is unchanged. Optional
    because support replay tooling mints messages no person triggered and transition-window
    producers send none — a required field would dead-letter both. A `userId` that *is* present
    must be a canonical UUID: anything else, an explicit `null` included, is a contract violation
    and dead-letters with a bounded reason that never quotes the value.
  - **Threading.** `CallerIdentity` is resolved once per run from the command and handed to the
    transformation and to every submission; the payload adapter reads it from the same command.
    Each client falls back to its own configured system identity
    (`informantregister.results.system-user-id`,
    `informantregister.referencedata.system-user-id`) when the message names nobody, so an
    environment that mounts one identity is still not asked for a second.
  - **Not logged, not fingerprinted.** The identity leaves the service in `CJSCPPUID` and nowhere
    else — no path, query, body or log line — and it is deliberately absent from
    `RequestFingerprint`, so a replay of an attributed request is the same unit of work rather than
    an idempotency collision.
  - **Rollout order.** The consumer ships first: a closed contract rejects an unknown field, so
    `cpp-context-results` must not send `userId` until this version is live in the target
    environment.
  - Restoring legacy behaviour needs no deviation entry; the one residue does. Deviations register
    **#16**: a *replayed* message runs under the system identity, where a legacy Event Grid retry
    re-delivered the original envelope and so kept the original user.
  - Agreed by the owner of both sides — publisher and consumer are the same team —
    **project-owner decision, 2026-08-23**.
  - **Post-review hardening.** Two assertions the change had been relying on prose for. A body whose
    optional `userId` is present but not a canonical uuid now joins the dead-letter corpus in
    `ContractValidationDeadLetterIT`, so "optional field, non-optional shape" is proven end to end
    on a real broker rather than at the parser alone. And `TelemetryPrivacyTest` now drives a run
    that names a user and asserts that identity appears in no captured line at any level —
    `CallerIdentity` said of itself that it is never logged, and that was a comment until now. The
    publisher side gained the matching guarantee: `RESULTS` parses the envelope's metadata `userId`
    before publishing, so a malformed value fails there, the way the Event Grid leg already fails
    it, instead of being published as a message that could only dead-letter here.
- 2026-08-23 — **`hearingStartTime` now says which hour of the day it means.** The one sanctioned
  departure from bug-for-bug parity in the transformation, taken as a project-owner decision:
  "for time lets use visually correct and semantically correct value - 14:30:00+01:00".
  Deviations register **#15**.
  - The legacy renders this component with `DateService.getLocalDateTime` — a London wall-clock
    *date-time* with the character `Z` appended whatever the real offset was (defect D9). A 14:30
    sitting in June went out as `2020-06-19T14:30:00Z`, an hour that did not happen at that instant,
    inside a component `informantRegisterHearing.json` types as `{"format": "time"}`. No value this
    component has ever carried satisfied its own schema.
  - It is now the same wall clock as an RFC 3339 `full-time` carrying London's **true** offset on
    the date, read from the `Europe/London` rules: `14:30:00+01:00` in June, `14:30:00Z` in January.
    The digits a clerk read off the courtroom clock are unchanged; the label stopped lying.
  - **Scope is that component alone.** `registerDate` and `hearingDate` keep D9's rendering, the
    parity pack's `d09` pin is untouched, the parse is untouched, and an unreadable sitting day
    still renders the literal `"Invalid dateZ"` (entry 13's territory).
  - **The goldens were not touched.** The recorded `expected.json` files are the Node oracle's truth
    and stay byte-identical, so the comparator gained a *derivation* for this component instead
    (`RegisteredFieldDeviations`, wired into `JsonParity`): it re-reads the wall clock out of the
    oracle's own value, asks the zone rules for that date's offset, and demands exactly that. Not an
    exclusion — a hard-coded `+01:00` fails **108** differential cases, and the comparator's own
    suite pins the rejections, the wrong-season offset included.
  - Differential suite after the change: **384 cases, 382 pass, 0 fail, 2 held back** on the
    pre-existing `s05` decision. Pinning pack: **19 asserted, 7 blocked**, unchanged.
  - ⚠ **Results must be told**: the CSV column the prosecuting authorities read changes shape, from
    a full date-time to a time of day.

### Added
- 2026-08-23 — **The register is addressed from real reference data.** `NowSubscriptionsSource` is
  served by `adapter/refdata/ReferenceDataNowSubscriptionsClient` in the deployed wiring; the
  refusing stub stays behind `informantregister.referencedata.mode=STUB` for local runs and for the
  container suites whose subject is settlement rather than who a register reaches.
  - The call is `ReferenceDataService.getSubscriptionsMetadata` ported: the path, the `on` query
    parameter, `Accept: application/vnd.referencedata.query.get-now-subscriptions+json` and
    `CJSCPPUID` (`ReferenceDataService.js:40-47`), all four confirmed against the reference-data
    query API's own RAML and against the parity pack's recorded calls.
  - **The `on` day is bug-for-bug.** It is the register date's own day, read with the misleading
    literal `Z` at face value (defect D9), so a hearing shared at 23:00 UTC in British Summer Time
    is addressed with the *next* day's reference data. Pinned by
    `RegisterTransformationChainTest.QueryDateAcrossBritishSummerTime` against the recorded case.
  - **The retry rule is the legacy's**, because this call goes through the same
    `AxiosRetryWrapper.getWrapperWithDefault` the payload fallback does: three attempts a second
    apart, and no retry at all once a response has arrived carrying a status at or below 429 — so a
    429 is tried once and a 500 three times. Ported deliberately and pinned.
  - **A failure is reported, not answered.** Connect, 5xx, 429, 404 and a body that is not JSON all
    raise the transient `ReferenceDataUnavailableException`; the legacy's `return null` is what makes
    an outage indistinguishable from "nobody is subscribed" (deviation 14, already on the register).
    An answer that *is* readable is passed through whatever its shape — an empty body, no
    `nowSubscriptions` member, or no informant-register subscription among them are business
    outcomes the legacy carries on from, and the matching step reads them.
  - **Startup refuses a live source that cannot ask**: no base URL, no `CJSCPPUID`, no attempts, a
    negative wait or a timeout that never expires each fail at startup rather than parking every
    hearing that produced a register from a pod reporting itself healthy. This closes, for this
    port, the LIVE-mode hole the payload story left open on its own; the payload one is untouched.
    The reference-data identity falls back to the Results one, because the function app threads a
    single `cjscppuid` through both calls.

- 2026-08-23 — **The transformation runs, and the parity pack is armed against it.** The three
  ported steps are chained as `InformantRegisterOrchestrator` chains them, behind a new
  `RegisterTransformer` port, and `DistributionPipeline` calls it between the payload fetch and the
  submission loop — so the loop that was written to execute zero times now executes once per
  prosecuting authority. The listener is untouched.
  - **384 differential cases** run the whole chain against recordings of the real function app
    (Node commit `a8d3c00b`, clock pinned) and compare with the golden comparator: **382 pass, 0
    fail, 2 are held back** on the open `s05` question and name it in the skip message. Each case is
    asserted against what the corpus says it is owed — an input the producer cannot send owes an
    explicit outcome and not Node's body, because eighteen of those recordings violate the frozen
    contract on the way out.
  - **26 pinning tests**, one per manifest entry: 19 assert against a recorded run, 7 are disabled
    carrying the manifest's own reason and the suite that does cover them. Six of the nineteen are
    places the port deliberately answers differently, all of them registered and none of them a
    register reaching an authority.
  - **The comparator's own contract** is pinned by the pack's 57 adversarial vectors, bound to
    `JsonParity`: 116 assertions, none skipped.
  - The reference-data fetch is a port (`NowSubscriptionsSource`) whose adapter is a later story.
    Until it exists the port **refuses** rather than answering "nobody is subscribed" — the two are
    indistinguishable downstream, and answering would let a real hearing be filed to nobody and
    recorded done. Deviations #14, sign-off pending.
  - Deviations #14 added. A completion now records *which* success it was: `no-authorities` or
    `authorities-submitted`.

### Fixed
- 2026-08-23 — **Three ways the now-subscriptions read could still address a register to nobody**,
  found by review of the adapter that had just landed.
  - **Success is 2xx and nothing else.** Spring's default handling raises on 4xx and 5xx alone, so a
    304 — or a redirect this client does not follow — arrived at the body reader as though reference
    data had answered, and an empty body there means "nobody is subscribed". That is the silent loss
    deviation 14 exists to end, reached through the one door it had left open. The rule is also the
    ported call's own: axios resolves 200-299 and rejects the rest
    (`axios/lib/defaults/index.js:161-162`, `axios/lib/core/settle.js:15-17`), so a 304 reaches
    `ReferenceDataService.js:50` exactly as a 502 does.
  - **The two contract headers are set, not appended.** A mesh header configured under the name
    `Accept` or `CJSCPPUID` used to be sent *alongside* the contract's value rather than instead of
    it — two `Accept` values is a 406 from a service doing content negotiation, and two `CJSCPPUID`
    values is an ambiguous caller to one authorising on identity. `ReferenceDataService.js:42-47`
    sends exactly one of each and now so does this.
  - **Startup refuses a read that can outlast the run.** Every reference-data timeout was checked
    for being positive and every attempt count for being at least one, and ten attempts against a
    minute-long read still passed — over ten minutes of waiting inside a four-minute deadline, so
    the claim becomes reclaimable and a second delivery starts processing the request while the
    first runner is still on the socket. The bound the payload fetch has had all along now applies
    to this read too: attempts × (connect + read), plus the waits between them, must be strictly
    shorter than `informantregister.claim.processing-deadline`. It is a per-fetch bound, as the
    payload one is; a combined budget across all three network steps would refuse the shipped
    defaults and is a decision for the design authority.

- 2026-08-23 — **Two ordered-date defects the differential corpus found**, both of which made the
  port refuse hearings the legacy files — the one direction a bug-for-bug port must not drift in.
  - **The parse format is a token walk, not the pattern it looks like.** `DateService.parse` is
    `moment(value, 'YYYY/MM/DD')` with no strict flag, so moment walks the format's tokens and gives
    each a width — up to four digits of year, two of month, two of day — skipping whatever separates
    them, and applies its two-digit-year rule on the way. `20-01-2020`, the ordered date both
    unmodified `OutboundInformantRegister` fixtures carry, is therefore 20 January 2020 and not a
    refusal. `HearingDates.orderingKey` reproduces the walk; the `moment.tz` fallback it used to
    share a pattern with is a different parser and is left alone.
  - **`Array.prototype.sort` decides whether a bad date is fatal.** It never calls the comparator on
    a lone element, and it moves `undefined` elements to the end without comparing them — so an
    unreadable ordered date is harmless alone and fatal in company (defect D10, pin `s06`), and a
    result carrying no ordered date cannot destroy a hearing at all. New `pipeline/OrderedDates`
    carries both rules for the two call sites that need them, and keeps an absent date apart from an
    explicit JSON null, because `sort` does.

- 2026-08-23 — **Post-review parity corrections to the aggregation mapper.** Every one is a place
  where the port answered differently from the Node source, found by reading the two side by side;
  each is traced to the legacy line it reproduces.
  - **An unreadable date is rendered, not refused.** `moment` does not throw — it flags the moment
    invalid and `format` answers the literal `"Invalid date"` — so `HearingDates.localDate` and
    `.localDateTime` now render `"Invalid date"` / `"Invalid dateZ"` where they previously raised a
    transformation failure. Both call sites read the payload directly: a sitting day
    (`CourtSessionMapper.js:26-28`) and a next hearing's start (`ResultDataMapper.js:15`). Refusing
    there parked hearings the legacy renders. `DateService.parse` really does throw and
    `HearingDates.orderingKey` still does, now including the case where three numbers read but are
    not a calendar day. Deviations #13 records the forms V8's date parser resolves and this does not.
  - **A null array member is a `TypeError`, and is refused rather than skipped.** The legacy
    dereferences each member as it iterates — `subscription.forDistribution`
    (`RecipientMapper.js:15`), `prompt.isFinancialImposition` (`ResultDataMapper.js:28`, inside
    `find`, so only up to the match), `offence.offenceCode` (`OffenceMapper.js:62`), `pcase.id`
    (`ProsecutionCaseOrApplicationMapper.js:55`). Reading a null as "nothing set" emitted a register
    the legacy never sent, to a real prosecuting authority. New `Json.dereferencedElement`.
  - **Three unguarded identifier dereferences** were being read null-safely and are now refusals:
    `prosecutionCase.prosecutionCaseIdentifier` at `OffenceMapper.js:17` (read for *every* case in
    the hearing, before any filtering) and `ProsecutionCaseOrApplicationMapper.js:20`, and the
    `deriveCaseUrn` argument at `OffenceMapper.js:41,51`.
  - **The legal-entity branch is reached lazily.** Every legacy site that dereferences
    `legalEntityDefendant.organisation` is an `else if`, so a defendant carrying a populated
    `personDefendant` beside an empty `legalEntityDefendant` maps cleanly there; resolving it up
    front refused the whole hearing.
  - **`orderIndex` is no longer truncated.** A fractional or oversized value is absent rather than
    silently narrowed to an `int` — truncating `1.5` to `1` produced a body that *passes* the
    consumer's schema carrying an index the payload never sent. Deviations #11.
  - **Identifier strictness at the typed boundary.** `UUID.fromString` accepts shorthand
    (`1-1-1-1-1` → `00000001-0001-…`), so the canonical 8-4-4-4-12 shape is checked before parsing.
    The case normalisation that remains — an upper-case identifier can only render back in lower —
    is now recorded on deviations #10, whose "byte for byte" claim was not true of it.
  - **`trim` is ECMAScript's.** New `JsStrings.trim` strips what `String.prototype.trim` strips,
    including `U+00A0`, which neither `String.trim()` nor `String.strip()` removes; used for email
    addresses (`RecipientMapper.js:41`) and the joined full name (`DefendantMapper.js:167`).
  - **Three assertions that did not check what they claimed**: the D8 pinning case now asserts each
    duplicated offence's `originatingCaseUrn` (on a hearing built here, because the byte-identical
    legacy fixture gives both cases the same reference and cannot show it); the `ResultMapper` twin
    asserts the whole multiline `resultText` its Jest original asserts, not a prefix; and the null
    email address is now asserted on the **serialised** body, where the omitted-key divergence is
    actually visible.
  - Deviations #11, #12 and #13 added, all sign-off pending; #10 amended. #12 records a collapse the
    fragment model cannot currently express — an id list cannot tell an absent id from an explicit
    null — and flags that closing it is a model decision for the parity review, not a mapper change.

### Added
- 2026-08-23 — **Subscription matching: who receives the register is now decided in Java.** The
  second transformation step is ported — `pipeline/SubscriptionRules` (the shared
  `SubscriptionsService` kernel) and `pipeline/SubscriptionMatcher` (the
  `InformantRegisterSubscriptions` activity) — producing
  `RegisterFragmentWithSubscriptions`, the fragment plus the subscriptions it matched.
  - The oddities are ported, not tidied: `ouCode` is the **major creditor code** rather than the
    authority's OU code, so on the twelve real hearing fixtures that carry no creditor code nothing
    can match; the whole register's vocabulary is the **first** defendant's; judicial results are
    pooled across every defendant; a subscription that is both a NOW and a prison-court-register
    subscription is matched **twice**, because the NOW branch has no `return`; and an
    `includedNOWS` of `[]` rejects every NOW, because an empty array is truthy.
  - An answer with nothing in it is not a failure. No body, no `nowSubscriptions` member, or none
    of them for the informant register, and the fragments come back with **no**
    `matchedSubscriptions` member at all — a different document from one carrying an empty array,
    and both shapes are pinned.
  - The step is **pure**: the legacy activity fetches reference data itself, and here the answer is
    passed in. `registerDate(fragments)` is the value that fetch must be dated with, and it is
    derived inside `match` as well, because the legacy dereferences it before the fetch and a
    fragment set carrying no register date must never reach reference data (blind spot BS-12).
  - Twinned twice over. All twenty-one `SubscriptionsService` Jest cases are twinned against
    byte-identical copies of their seven fixtures, asserting *which* subscriptions came back and not
    only how many — every Jest case asserts a length alone, and **three** of them are named for
    branches they never enter. The three `InformantRegisterSubscriptions` cases are twinned as what
    they actually are: all three mock reference data with a bare array and return before any matching
    runs, which is blind spot **BS-01**, the largest false-confidence surface in the legacy suite.
  - Six further cases close BS-01 against goldens captured from the **real** legacy activity chain
    (parity-pack `recorded/` inputs, Node commit `a8d3c00b`, clock pinned), covering the
    informant-register filter, the empty-match short-circuit, the creditor-code wiring, duplicate
    subscriptions and the vocabulary gate actually refusing a match.

- 2026-08-23 — **The aggregation mapper: a fragment becomes the command body that is sent for it.**
  The last of the three transformation steps, ported from `OutboundInformantRegister/index.js` and
  its eight-mapper tree into `pipeline/`, producing the typed `InformantRegisterDocument` that
  already existed in `domain/`. Pure — a hearing tree, a fragment and its matched subscriptions in,
  one document out — so the golden files alone decide whether the port is right.
  - **Forty-five Jest cases have JUnit twins**, against byte-identical copies of the legacy's
    fifteen fixtures, plus a Java translation of the `ModelObjects.js` builder six of those files
    depend on. Where the Jest suite mocks a collaborator away, the twins do not: the fixture is
    completed instead, and the case the mock was hiding gets its own assertion.
  - **Whole-document golden parity** on the three activity-level cases, compared field for field
    against output captured by invoking the real legacy handler. The transcribed Jest assertions
    check the fields their author happened to name; the goldens check every field of every offence
    of every case of every defendant.
  - **The oddities are pinned, not tidied.** D8 (every case entry carries the defendant's whole
    offence list, each offence still naming the case it really came from), D9 (`hearingStartTime`
    and `registerDate` are London wall-clock time labelled `Z`, asserted as exact strings with a
    January control beside them), D11 (duration dates are re-read as `DD/MM/YYYY` and anything else
    becomes the literal `"Invalid dateZ"`), D17 (letter delivery is logged and ignored, and a
    recipient with no address is dropped silently), s04 (a case reference is its URN when *truthy*,
    not when non-null) and the file name's second reading of the register date, `undefined` included
    when an authority has no code.
  - **Five of the parity pack's coverage findings are answered rather than inherited** — BS-05
    (cross-authority offence isolation, whose false leg the legacy suite never runs), BS-06
    (organisation defendants, a whole defendant class with no assertion anywhere), BS-07
    (application-level results, unexecuted repo-wide), BS-09 and BS-10 (every optional-value false
    leg of the recipient and result-data mappers), BS-14 and BS-15. Each path is ported from the
    source and covered by a deterministic case; none is left to the first real hearing to discover.
  - Two new deviations, both **sign-off pending**: #9, an unmapped verdict code yields a verdict
    with no type rather than a null one, which the closed contract cannot carry; and #10, a
    register component that cannot be typed is parked and dead-lettered rather than POSTed for the
    consumer's schema to reject. Neither changes a register that the legacy successfully sends.
  - `HearingDates` gains `formattedLocalDateTime`, the `DateService.formatDateAndGetLocalDateTime`
    port, whose every expectation was taken from the `moment` build vendored with the function app
    rather than from a reading of what it ought to do.
- 2026-08-22 — **The submission leg: `add-informant-register` is actually POSTed.** The stub
  submission client is replaced by `adapter/results/`, which posts one command per prosecuting
  authority to the results-owned endpoint at the exact vendor media type, and by the
  `processed_output` half of the processed log, which has existed since V1 and until now stayed
  empty (no new migration — the schema was already complete).
  - The row is claimed **before** the POST, carrying `request_digest` — SHA-256 of exactly the bytes
    sent — and moved to `POSTED` or `FAILED` afterwards, so a POST whose outcome is never learned
    still leaves evidence of what was attempted. The claim and the skip are one conditional upsert
    rather than a read and then a write, because two deliveries of a request can be in flight and a
    `SELECT` is stale the moment it returns. An authority already `POSTED` is skipped, so partial
    progress survives a redelivery or a replay.
  - Retry classification is the one sanctioned behaviour change (`doc/DEVIATIONS.md` #2): connect
    and read failures, dropped connections and 5xx are retried with a doubling wait; 429 is retried
    after the delay the server asked for, capped so a misconfigured server cannot park a run past
    its claim; any other 4xx is a refusal, is never retried, and comes back `NON_TRANSIENT` under
    the new bounded reason `SUBMISSION_REJECTED`. An **ambiguous** outcome is retried, preferring a
    duplicate the 19:00 sweep absorbs over a loss nothing does — and no code or comment promises
    more than that.
  - `AuthoritySubmission` gains the request's key, because `processed_output` is keyed
    `(source, request_id, prosecution_authority_id)` and an authority identifier alone cannot name
    that row.
  - Endpoint, identity and retry policy are typed configuration under `informantregister.results.*`.
    `CJSCPPUID` is the one documented header; because the authorisation scheme is **not** documented
    anywhere, any further header is configuration rather than a guess in code.
  - WireMock joins the build for this: one exact path, one exact vendor media type and a body the
    results-owned schema accepts are not assertable against a mocked HTTP client, which agrees with
    whatever the code does.

### Fixed
- 2026-08-23 — **A `null` in a reference-data or results array is refused, not quietly skipped.**
  Wherever the legacy reads a property off an array element — `nowSubscriptions`
  (`InformantRegisterSubscriptions/index.js:28`), a candidate or child subscription, a judicial
  result (`SubscriptionsService.js:213`, `:234`, `:286`) or one of a result's prompts (`:224`,
  `:287`) — a `null` element is a `TypeError` there and the hearing produces **no register for any
  authority**. The port had been reading it as "a candidate that matches nothing" and carrying on,
  which could hand a real prosecuting authority a register the legacy never sent. It is now
  `TransformationFailedException`, dead-lettered, under `doc/DEVIATIONS.md` entry 7 with every other
  legacy `TypeError`.
  - `Json.dereferenced` reproduces the legacy's *reach*, not a blanket refusal. `some` and `find`
    stop at the first answer, `filter` completes its pass whatever it has already found, and
    `[].some(cb)` never runs `cb` at all — so an empty `includedResults`/`excludedResults` list still
    answers false without reading a judicial result, because refusing there would lose a register the
    legacy produces.

### Changed
- 2026-08-23 — **The subscription-matching coverage claim now matches the coverage.** Prompted by
  review, the branch coverage of the two matching classes was measured rather than asserted:
  `SubscriptionRules` was at 151 of 272 branches and `SubscriptionMatcher` at 22 of 26, against a
  changelog and a test javadoc that read as though BS-01 were closed.
  - The Jest twin named *"Should Not include subscriptions if excluded Prompts are matched with
    result prompts"* was the third case found to pass for a reason its name does not describe: the
    subscription first demands the included prompt `suretyNameAndAddress`, which the fixture's
    results do not carry, so `excludedPrompts` was never evaluated and the case would have passed
    with that logic deleted. It now asserts what actually decided it, and the branch it is named for
    is driven separately.
  - New cases drive what the twenty-one Jest cases and the six goldens leave unexecuted: EDT-only
    matching, a rejected child subscription, a prison-register candidate the vocabulary gate refuses,
    an informant-code match, the case-sensitivity of both code comparisons, `includedNOWS: []`
    against an absent one, `userGroupVariants` as `[]` and as `null`, a missing
    `subscriptionVocabulary`, the CPS shortcut and its `=== true`, every attendance, court, defendant,
    custody and custodial-result combination the legacy writes, both included/excluded prompt and
    result outcomes, a reference-data prompt with no `resultPromptReference`, and every positive
    major-creditor path — which the informant register itself can never reach, because its vocabulary
    is built with the two-argument constructor.
  - Two claims the six goldens cannot decide are separated on hand-built fragments, because no
    recorded hearing distinguishes them: the register's vocabulary being the **first** defendant's
    (`index.js:46`) and judicial results being **pooled across every** defendant (`:53-64`). Both were
    verified by mutation — inverting either one leaves all six goldens green and fails these.
  - `SubscriptionRules` is now at 240 of 276 branches and `SubscriptionMatcher` at 23 of 26; the
    residue is defensive null halves, not unpinned matching behaviour.

### Fixed
- 2026-08-23 — **The startup time budget now covers the whole payload fetch** (second Story 1
  review round):
  - the budget counted the query-side attempts only, so the two cache reads in front of them — the
    dated key and its legacy undated twin, each able to spend a connect and a command timeout —
    were time a run could spend beyond the deadline the budget exists to hold it to. They are
    counted now, and the fetch must be *strictly* shorter than the processing deadline: a fetch
    that fills it exactly leaves the rest of the run nothing, which is the reading
    `DistributionPipeline` and the lease rule already take of that bound;
  - the cache and query-side settings are asked of the live source only. STUB builds neither
    client, so a local stub run is no longer refused startup over a cache address and an attempt
    count that nothing in it will read — the same scope the system-user identity rule already had.
- 2026-08-22 — **Post-review hardening of the payload adapter** (Story 1 review, findings
  re-verified against the function-app source before fixing):
  - the composite adapter no longer catches every `RuntimeException` the cache can raise. The
    catch moves down to the cache adapter, which knows its own technology, and narrows to
    `RedisException` — so a cache outage is still a miss the query side can answer, while a defect
    in this service reaches the pipeline and is recorded instead of being spent on a fallback;
  - two behaviours that differ from the function app are now named in `doc/DEVIATIONS.md` with
    their assertions: reading the legacy undated cache key as a second lookup (entry 4), and
    treating a cache that cannot be *connected* to as a miss rather than as the end of the fetch
    (entry 5). Registered deviation 1, verified TLS, gained the assertion it never had;
  - neither parse failure — a corrupt cached value or a malformed query response — is logged with
    the parser's own words any more; they quote the token they stopped on, and in a hearing
    document that token is defendant data;
  - startup now refuses a payload source that could never fetch: LIVE without the system user
    identity its fallback authorises with, STUB where the deployed credential source is in use
    (constitution Principle V, now that the real adapter has landed), a fallback allowed no
    attempts, a timeout that never expires, a cache with no address or key prefix, and a fallback
    whose worst case outlasts the processing deadline it runs inside;
  - the source-selection tests are twinned against the function app's own Jest fixture, copied
    byte-identical into `src/test/resources/fixtures/`, and the live adapter is now exercised
    through the whole service — cache hit to COMPLETED, and a refused query read to FAILED and
    parked — which no suite covered before.
- 2026-08-22 — **Post-review parity corrections to the ported transformation.** Every finding was
  re-verified against the Node source under
  `cpp-context-azure-legalaidagency/azure-functions/durable-functions/` before it was fixed, and
  four of them were places where the port was **more forgiving than the legacy** — the one direction
  a bug-for-bug port must never drift in, because a payload the function app throws on would have
  produced a register here and sent it to a real prosecuting authority:
  - arrays the legacy dereferences with no `|| []` and no enclosing `if` are now refused when they
    are absent, not read as empty. `Json.dereferencedArray` is the form that says so, and each call
    site names the legacy line it reproduces; `Json.array` keeps the guarded semantics and is used
    only where the legacy guards;
  - a `"courtCentre": null` is refused like a missing one, because `null.welshCourtCentre` is the
    same `TypeError` — it was being read as "not Welsh" and marking the hearing English;
  - a court application whose `judicialResults` is not an array is **skipped**, not refused: the
    legacy test is `judicialResults.length > 0`, and `undefined > 0` is false, so it carries on with
    the rest of the application. The port was failing a hearing the legacy turns into a register;
  - a slash-separated day now formats the way `moment.tz` formats it — through the `new Date(...)`
    fallback, read as UTC and then converted, so an hour later in British Summer Time — instead of
    raising an unclassified `DateTimeParseException`. Verified against the vendored
    `moment-timezone`, and host-time-zone independent;
  - the classification the ports carry is now the branch it was always documented to be: a
    `NON_TRANSIENT` failure is recorded FAILED and dead-lettered at once instead of being handed
    back until the delivery budget ran out and parked under `DELIVERY_LIMIT_EXHAUSTED`. New guard
    transition `recordNonTransientFailure`, and the first producer of `DeadLetterReason.NON_TRANSIENT`;
  - deviations register: new entry **7** records the transformation's own swallowed exceptions, which
    the code had been attributing to entry 2 — that entry covers the final POST only.
- 2026-08-22 — **Second review pass over the submission leg** (findings re-verified against the
  rules and the Node source before fixing; the ones that contradicted a rule were rebutted, not
  applied):
  - `INFORMANT_REGISTER_SYSTEM_USER_ID` now reaches something. `application.yaml` documented the variable in a
    comment and bound no key to it, so a deployment that set the identity correctly still refused to
    start with "system-user-id is required". It is bound as `${INFORMANT_REGISTER_SYSTEM_USER_ID:}`, in the
    same shape as `RESULTS_BASE_URL`, and the shipped file's binding is now asserted against the
    real file rather than against property values a test invents;
  - the retry policy is validated at startup alongside the claim timings and the credential rule.
    `max-attempts` below one attempted no POST at all and handed every hearing back as an unresolved
    transient failure — silent non-delivery wearing a retry policy's clothes. A negative
    `initial-backoff` and a `max-backoff` below it are refused for the same reason: they fail
    quietly at runtime and loudly at startup;
  - a transport failure is logged with the exception rather than with its class name. The
    classification is what the pipeline settles on; a refused connection, a read that timed out and
    a dropped route are three different investigations, and the bounded reason code the failure is
    reported under cannot carry the difference;
  - the idempotency gate is now proven across the real repository, a real store and a real socket
    (`SubmissionRedeliveryIT`): a redelivery does not re-POST an authority that went, and a
    redelivery after a partial failure repeats exactly the authority that did not. The existing
    suites proved each half against a mock of the other;
  - `doc/DEVIATIONS.md` #8 records the `Retry-After` treatment that #2 implied but did not state.
- 2026-08-22 — **Post-review hardening of the submission leg** (review of the story-3 change,
  findings re-verified against the rules and the contract before fixing):
  - a failure that carries a classification is now settled on it. A refusal from the Results command
    was being caught as an unexpected runtime failure, recorded `UNEXPECTED_FAILURE` and retried to
    exhaustion; it is now parked on the delivery that met it, under the reason it carried
    (`SUBMISSION_REJECTED`) and the dead-letter category `non-transient`. The guard grew
    `recordNonTransientFailure` for it, and the data model's transition table names the row;
  - `POSTED` is terminal in `processed_output`, enforced by the statements rather than by
    convention: a runner whose claim was reclaimed while it worked can no longer move an authority
    the winner had already posted back to `FAILED`, which would have had the next delivery re-claim
    it and POST a second, non-idempotent register;
  - the adapter checks that its outcome writes landed and reports an overlap at ERROR instead of
    discarding the affected-row count;
  - `AuthoritySubmission` carries an `InformantRegisterDocument` rather than a `JsonNode`. The tree
    was a placeholder for a document type that did not exist yet; it exists, and constitution
    Principle IV asks the compiler — not a runtime schema check — to keep an unnameable field out of
    a closed contract;
  - `CJSCPPUID` is required. The gateway refuses to be built without one, so a deployment missing the
    identity fails to start instead of dead-lettering every hearing it is given, one 403 at a time;
  - success is `202 Accepted` and nothing else: any other 2xx is reported non-transient under the new
    `SUBMISSION_NOT_ACCEPTED` rather than marking an authority POSTED for a command nothing enqueued
    (`doc/DEVIATIONS.md` #2 extended to say so);
  - `Retry-After` is matched before it is read, so an unusable header is classified rather than
    raised and caught. The delta-seconds-only rule stands and is now pinned by a test: honouring an
    HTTP-date would measure a remote clock against this pod's, and a server minutes ahead would park
    a run past the claim it holds.
- 2026-08-21 — **Post-review hardening of the walking skeleton** (whole-`src/` review, findings
  independently re-verified before fixing):
  - an unexpected exception inside an admitted run is now recorded through the guard (RETRYING, or
    FAILED + dead-letter on the final permitted delivery) instead of escaping with the run claim
    still live and letting the broker park the message with no record behind it;
  - lock loss is learned from the broker's refusal of the one settlement attempt and counted under
    the lock-loss instrument, replacing the local-clock `lockedUntil` pre-check that skew could
    turn into skipped settlements;
  - only store-outage exception classes suspend intake; a constraint violation or broken statement
    hands its delivery back without stopping the queue;
  - a consumer the broker has never answered no longer ages its startup fault into a healthy
    reading: it keeps one startup grace window and then reports DOWN until first contact;
  - `source` joins `requestId`/`hearingId`/`hearingDay` in the MDC on every message, including the
    contract-invalid path (canonical values only);
  - completing a previously retried request clears `failure_reason`, so a COMPLETED row never
    carries a stale failure (data model updated to state the semantic);
  - the workflow message-contract gate text now matches the closed contract the schema declares —
    unknown extra fields dead-letter; they were never tolerated.

### Added
- 2026-08-20 — Repository scaffolded from `hmcts/service-hmcts-crime-springboot-template`
  (Spring Boot 4.1, Java 25, Gradle, package root `uk.gov.hmcts.cp`).
- 2026-08-20 — Spec-kit bootstrap: `.claude/rules/` adapted for this service —
  `design_rules.md` (message-driven ports-and-adapters pipeline, processing state machine,
  queue semantics, idempotency log, parity/deviations rule), `workflow.md` (message-contract
  and golden-parity gates in place of the API-first gate), `technical-rules.md` and
  `technical-default.md` (stack facts, messaging and no-swallowed-exception conventions).
- 2026-08-20 — Project documentation seeded from the agreed Option 2 design (19 Aug 2026):
  `doc/SOLUTION_BRIEF.md`, `doc/TECHNICAL_DESIGN.md`, `doc/API_CONTRACTS.md`.
- 2026-08-20 — **CRA-220 "Informant register - Initial POC" started.** Walking skeleton in
  flight: ASB consumer with peek-lock settlement discipline, `(source, requestId)` idempotency
  guard, ports with stub adapters (payload fetch and register submission as logging no-ops),
  actuator and container build.
- 2026-08-21 — **CRA-220 walking skeleton delivered.** A message now travels the whole path and is
  accounted for at the end of it:
  - **Intake** — Service Bus processor on `informantregister.requests` in peek-lock with
    auto-complete off; exactly one explicit complete / abandon / dead-letter per delivery, and the
    settlement guard is exactly one broker call wide so nothing after it can be reported as a
    refusal. Contract-invalid bodies are dead-lettered before any record exists.
  - **Contract** — `distribution-command.schema.json` parsed into the `DistributionCommand` record,
    dual-validated against parser and schema over a corpus, with unknown fields and out-of-enum
    `source`/`eventType` pinned.
  - **Idempotency** — the `processed_request` log (Flyway `V1`) and a conditional-update claim: at
    most one run in flight per `(source, requestId)`, claims reclaimable after their lease, stale
    runners rejected by owner+token, `FAILED` replay decided by broker message identity, and a
    fingerprint collision dead-lettered with the record untouched.
  - **Store outages** — a store that stops answering suspends intake and abandons the delivery
    rather than burning `maxDeliveryCount`; migration is deferred off context refresh and runs on
    the first successful probe, so nothing is consumed against an unmigrated schema.
  - **Health and telemetry** — the store gates readiness and the queue never does (a broker blip
    must not roll the pods); a passive `servicebus` health component plus
    `informantregister_servicebus_up`; correlation-only MDC with no payload logging, and an ERROR
    log and a named failure metric on every failure path.
  - **Packaging** — Dockerfile, `docker-compose.yml` (Postgres + pinned Service Bus emulator 1.1.2
    sharing one queue definition with the Testcontainers harness) and `scripts/container-smoke.sh`,
    which CI runs as the `Container-Smoke` job.
  - The submission and payload adapters remain deliberate logging stubs; an empty authority set is
    this increment's correct outcome, not a missing step.
- 2026-08-21 — CRA-220 handover written (what the increment still needs outside this repository); maintained outside the repo with the workstream's analysis notes
  (queue and DLQ provisioning, workload identity and Key Vault CSI, Flux/ADO wiring, the GitHub
  remote, and the operability and Results-publisher follow-ups).

### Changed
- 2026-08-21 — Git/CI policies aligned with `service-cp-crime-hearing-results-validator`:
  workflows rebuilt on its `main` (SHA-pinned actions, `team/**` triggers, branch-aware artefact
  versioning via `gradle.properties` `projectVersion`, split Build/Test jobs, Trivy image scan,
  release-notes image coordinates, CodeQL config excluding test sources, secrets-scanner on push);
  branch-protection ruleset added as code (`.github/rulesets/main.json`, import manually);
  CODEOWNERS set to `@hmcts/results-validation-service-team`; Dependabot 14-day cooldown;
  `.editorconfig` and `settings.gradle` added. The validator's `API-Test` job is replaced by an
  unconditional `Container-Smoke` job (`scripts/container-smoke.sh`) — this service has no REST
  surface — and there is no `validate-api-spec-version` gate (no apiSpec dependency).
- 2026-08-21 — Checkstyle adopted (`config/checkstyle/google_checks.xml`, tool 10.25.0,
  `maxWarnings = 0`, main sources only, wired into `check`); existing violations fixed
  (import order, javadoc summaries, one indentation). Supersedes the earlier
  "no Checkstyle in this build" stance — agreed 21 Aug 2026.
- 2026-08-21 — JaCoCo coverage gate added (`jacocoTestCoverageVerification` in `check`:
  LINE ≥ 0.88, BRANCH ≥ 0.85, excluding `Application` and `config/**`), matching the
  validator's ratchet. Thresholds to be re-checked against measured coverage once CRA-220
  development settles.
- 2026-08-20 — `doc/openapi.yaml` reduced to a comment-only stub: this service has no REST API
  (actuator only). Its contracts are the inbound ASB message and the results-owned
  `add-informant-register` command — see `doc/API_CONTRACTS.md`.
