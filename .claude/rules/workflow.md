# Workflow: Mandatory Build Loop

Every non-trivial code change MUST follow this cycle:

```
Contract → Failing test → Write → Code Review (agent) → QA (agent) → Contract Validate (agent) → Fix → Ship
```

- **Contract:** this service has **no OpenAPI spec**. Its contracts are:
  1. the **inbound ASB message schema** — `doc/API_CONTRACTS.md` plus the JSON schema under
     `src/main/resources/contracts/`, and
  2. the **outbound `add-informant-register` command** — owned by `cpp-context-results`
     (`results-command-api.raml`, `additionalProperties: false`), consumed here, never redefined here.

  Update the contract documentation BEFORE writing code that changes either shape. A change to the
  outbound body is a change to somebody else's contract: raise it with Results first, never widen it
  locally.
- **Contract Validate:** run the `spec-validator` agent to check the code against the message
  contract and the parity gates (below) — not against an OpenAPI file.

Loop repeats until ALL agents return PASS / COMPLIANT.

## What Requires the Loop

| Must Go Through Loop                            | Exempt                         |
|-------------------------------------------------|--------------------------------|
| New / modified Java class                       | Markdown / docs only           |
| New / modified test class or golden fixture     | Whitespace / import only       |
| Message-contract or schema change               | CLAUDE.md and rule updates     |
| Flyway migration                                | README changes                 |
| ASB consumer / settlement configuration         |                                |
| Dockerfile changes                              |                                |
| CI/CD pipeline config                           |                                |
| Helm chart or values                            |                                |

## TDD is Non-Negotiable

- Write the failing test first; confirm it fails for the *correct* reason (assertion failure, not a
  compilation error)
- Then write the minimum production code to make it pass
- Then refactor with the test still green
- Commit history MUST show the failing test was authored at or before the production code —
  **every commit**, no exceptions, no "tests to follow"

## Gates (replace the API-first gate)

A change ships only when all applicable gates are green:

1. **Message-contract gate.** The inbound message is parsed and validated against the documented
   schema. Unknown fields, missing required fields, and unparseable messages have explicit,
   tested behaviour: missing required fields and unparseable messages dead-letter
   with a reason (never a silent drop); unknown extra fields are tolerated
   (forward-compatible) and logged once at debug.
2. **Settlement gate.** Every path through the message listener performs exactly one explicit
   `complete()` / `abandon()` / `deadLetter()`. Tests must cover the success, transient-failure and
   non-transient-failure paths. A path that can return unsettled fails review.
3. **Idempotency gate.** Redelivery of the same `(source, requestId)` must not produce a second
   submission. Proven by test, not by inspection.
4. **Golden-parity gate.** Every ported behaviour has a JUnit twin of the corresponding Jest case,
   running against the byte-identical fixture, and it passes. Recorded hearing payloads match golden
   output.
5. **Deviations gate.** Any difference from function-app behaviour is a named entry in
   `doc/DEVIATIONS.md` with its own assertion. The harness fails on any *unregistered* difference.
   "It looked wrong so I fixed it" is a failed gate.
6. **No-swallowed-exception gate.** No empty catch, no catch-and-continue, no success returned from
   a catch block. Reviewers reject on sight.
7. **No-PII gate.** No defendant PII (names, addresses, DOB, ASN, URN) at `info` level or above.

## Agent Definitions

### code-reviewer (Read only)
- Spawned as sub-agent with Read-only tools
- Analyses code for: logic errors, null safety, ports-and-adapters violations (infrastructure types
  leaking into the application layer), swallowed exceptions, unsettled message paths, secrets, PII in
  logs, `System.out` usage
- Returns: **PASS** or **NEEDS CHANGES** with severity-rated findings
- NEVER modifies code — reports only

### qa (Read only)
- Spawned as sub-agent with read-only tools; `Bash` for inspection and for running the existing
  suite only
- Judges the tests that exist against the test matrix in `.claude/agents/qa.md` (JUnit 5 + Mockito +
  AssertJ; Testcontainers `servicebus-emulator` and Postgres; WireMock for HTTP stubs) and names
  every gap
- Verifies TDD discipline (test tasks ordered before implementation tasks; a red run recorded before
  the green run)
- Runs `./gradlew test`
- Returns: **PASS** or **FAIL** with test results and missing-coverage findings
- NEVER writes tests and NEVER fixes production code — reports only

### software-engineer (Full access)
- For full feature implementation tasks
- **Writes every test**, test-first, under the TDD rule below — the `qa` agent reviews them, it does
  not author them
- Follows all rules in `technical-rules.md` and `design_rules.md`
- Runs `./gradlew build` after changes

### spec-validator (Read only)
- Spawned as sub-agent with Read-only tools
- **There is no OpenAPI spec to validate against.** Instead it checks:
  - the inbound message record and its validation against `doc/API_CONTRACTS.md` and the JSON schema
  - the outbound `add-informant-register` body against the results-owned schema (no extra fields —
    the command is `additionalProperties: false`)
  - settlement discipline: one explicit settlement on every listener path
  - state-machine completeness: every terminal path persists a status before settling
  - golden fixtures present and referenced; deviations register consistent with the assertions
- Returns: **COMPLIANT** or **DRIFT DETECTED** with severity-rated findings
- NEVER modifies code — reports only

### research (Read, Glob, Grep, WebSearch)
- For deep codebase investigation, including the Node source under
  `cpp-context-azure-legalaidagency/azure-functions/durable-functions/` when establishing parity
- Cross-references design documents
- Returns structured findings with citations

## Critical Principle

**Agents are reporters, not fixers.** The parent agent (or developer) reads agent reports and applies
all fixes. This prevents conflicting changes and keeps the team in control.
