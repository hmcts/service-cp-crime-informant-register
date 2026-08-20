# Workflow: Mandatory Build Loop

Every non-trivial code change MUST follow this cycle:

```
Spec → Write → Code Review (agent) → QA (agent) → Spec Validate (agent) → Fix → Ship
```

- **Spec:** Define or update `doc/openapi.yaml` BEFORE writing code (API-first)
- **Spec Validate:** Run `spec-validator` agent to check code matches the OpenAPI spec

Loop repeats until ALL agents return PASS / COMPLIANT.

## What Requires the Loop

| Must Go Through Loop            | Exempt                         |
|---------------------------------|--------------------------------|
| New / modified Java class       | Markdown / docs only           |
| New / modified test class       | Whitespace / import only       |
| Liquibase migrations            | CLAUDE.md and rule updates     |
| Dockerfile changes              | README changes                 |
| CI/CD pipeline config           |                                |
| Helm chart or values            |                                |

## Agent Definitions

### code-reviewer (Read only)
- Spawned as sub-agent with Read-only tools
- Analyses code for: logic errors, null safety, DDD violations, secrets, patterns
- Returns: **PASS** or **NEEDS CHANGES** with severity-rated findings
- NEVER modifies code — reports only

### qa (Read, Write, Bash)
- Spawned as sub-agent
- Generates test classes (JUnit 5 + Mockito + MockMvc)
- Runs `./gradlew test`
- Returns: **PASS** or **FAIL** with test results
- NEVER fixes production code — only writes tests

### software-engineer (Full access)
- For full feature implementation tasks
- Follows all rules in technical-rules.md
- Runs `./gradlew build` after changes

### spec-validator (Read only)
- Spawned as sub-agent with Read-only tools
- Reads `doc/openapi.yaml` and compares against controller implementations
- Checks: endpoint coverage, schema compliance, response codes, content types
- Returns: **COMPLIANT** or **DRIFT DETECTED** with severity-rated findings
- NEVER modifies code — reports only

### research (Read, Glob, Grep, WebSearch)
- For deep codebase investigation
- Cross-references design documents
- Returns structured findings with citations

## Critical Principle

**Agents are reporters, not fixers.** The parent agent (or developer) reads agent reports and applies all fixes. This prevents conflicting changes and keeps the team in control.
