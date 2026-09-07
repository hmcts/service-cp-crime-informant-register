# Contracts — CRA-220 walking skeleton

This feature's external surface is messaging, not HTTP. The contracts are **not duplicated here**
to avoid drift; this file points at the canonical, versioned artefacts.

## Inbound — distribution request message

- **Canonical schema**: `src/main/resources/contracts/distribution-command.schema.json`
  (draft-07, `additionalProperties: false`, six required fields). This is the file contract tests
  assert against and the only place the machine-readable contract lives.
- **Narrative + failure semantics**: `doc/API_CONTRACTS.md` §1 (message identity, replay rules,
  failure-behaviour table).
- **Ownership**: joint with `cpp-context-results` (the publisher). Changes are a cross-team event
  (constitution Principle III).

## Outbound — `add-informant-register`

- Results-owned, frozen; documented in `doc/API_CONTRACTS.md` §2. **Not exercised this increment**
  — the submission port is served by a logging stub; the real client arrives with a later story.

## HTTP

- None beyond operational actuator endpoints (health/liveness/readiness, info, metrics,
  Prometheus). There is no OpenAPI specification and no business endpoints.
