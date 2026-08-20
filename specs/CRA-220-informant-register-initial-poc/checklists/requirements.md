# Specification Quality Checklist: Informant Register Service — Initial POC (walking skeleton)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-20
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- **Review rounds**: the spec has been through review gate rounds on 20 Aug 2026. The artefacts were
  revised in response to each round; this note records those revisions, not a completed gate — the
  gate is closed by the reviewer, not by this checklist. After each round every item above was
  re-checked against the revised artefacts, and items are ticked only where the current artefacts
  make them true.
  - **Round 1** (18 findings, all applied to `spec.md`): the state machine starting only after
    contract validation (so a contract-invalid message gets no record); an explicit state transition
    table; store-outage handling as a precondition with intake suspension; cumulative attempt-count
    semantics separated from the broker's per-message delivery count; FAILED → RECEIVED guarded by
    message identity; dropping "exactly once" in favour of the real invariants; a single rule for a
    competing non-terminal delivery; stale-claim recovery; failure classification;
    processed-output scope; settlement edge cases; failure observability with alert wiring deferred;
    the no-business-HTTP-surface wording; readiness gating and queue health visibility; idempotency
    collisions; and privacy precision at INFO level and above.
  - **Round 2** (residual reconciliation plus three wording defects, all applied): the behavioural
    rules FR-001..FR-018 were settled at this point and were not changed. The companion documents
    were brought into line with them — `doc/TECHNICAL_DESIGN.md` for `attempts` semantics, the
    existing-record branches of the state machine (COMPLETED, FAILED by message identity, contested
    and stale single-runner claims, fingerprint collision), the `processed_request` columns those
    branches require, the deferral of `processed_output` semantics to the real-submission stories,
    and the reclassification of lock loss as log-and-count with broker redelivery rather than
    abandon-and-retry; and `doc/API_CONTRACTS.md` for the same `processed_output` deferral and for
    the replay exception to the deterministic message identity. The three wording defects were in
    `spec.md`: the missing in-flight outcome in SC-001, the repeated-crash wording in SC-002, and
    the matching invariant in the User Story 2 list. This checklist's own review-round note was
    rewritten in the same round.
- **Retained deliberate boundaries** (deliberate, not oversights):
  - the queue's mechanics (delivery limit, duplicate detection, dead-letter queue, message identity)
    appear in the spec because they are the externally observable contract of the service's trigger,
    not an internal technology choice — the same reasoning covers FR-014 naming the operational
    endpoint family (health, info, metrics, Prometheus), which is an operational surface operators
    depend on rather than an internal design decision;
  - the processed-log store technology remains explicitly deferred to planning;
  - the processed-output record is schema-only in this increment — the stub pipeline produces no
    outputs, so no rows are written and the replay/skip semantics are specified with the real
    submission stories.
- Items marked incomplete require spec updates before `/speckit.clarify` or `/speckit.plan`
