# Skill: Generate Project Documents

Generate or update the standard Modern by Default (MbD) project documents.

## Documents

| #  | Document           | File                        | Purpose                          |
|----|--------------------|-----------------------------|----------------------------------|
| 1  | Solution Brief     | doc/SOLUTION_BRIEF.md       | Problem, solution, integrations  |
| 2  | Technical Design   | doc/TECHNICAL_DESIGN.md     | Architecture, classes, patterns  |
| 3  | API Contracts      | doc/API_CONTRACTS.md        | Endpoint specs, request/response |
| 4  | Event Contracts    | doc/EVENT_CONTRACTS.md      | Event schemas (if event-driven)  |
| 5  | Migration Plan     | doc/MIGRATION_PLAN.md       | Legacy transition strategy       |
| 6  | Runbook            | doc/RUNBOOK.md              | Operations, monitoring, alerts   |
| 7  | Changelog          | doc/CHANGELOG.md            | Version history                  |

## Instructions

When invoked, generate or update the requested documents by:
1. Reading the current codebase to understand what is implemented
2. Reading existing design documents for context
3. Generating accurate documentation that reflects the actual implementation
4. Flagging any drift between docs and code

## Output

Return a compact status table:

```
| Document         | Status  | Notes                    |
|------------------|---------|--------------------------|
| SOLUTION_BRIEF   | Updated | Added integration details |
| TECHNICAL_DESIGN | Created | 12 classes documented    |
| ...              | ...     | ...                      |
```
