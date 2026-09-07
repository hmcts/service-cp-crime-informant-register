# Skill: Generate Project Documents

Generate or update the standard Modern by Default (MbD) project documents.

## Documents

| #  | Document           | File                        | Purpose                          |
|----|--------------------|-----------------------------|----------------------------------|
| 1  | API Contracts      | doc/API_CONTRACTS.md        | Inbound message + outbound command |
| 2  | Event Contracts    | doc/EVENT_CONTRACTS.md      | Event schemas (if event-driven)  |
| 3  | Runbook            | doc/RUNBOOK.md              | Operations, monitoring, alerts   |
| 4  | Changelog          | doc/CHANGELOG.md            | Version history                  |

Solution-brief and technical-design content is **not** generated into this repo. Design is
maintained on the [Informant Register Service](https://tools.hmcts.net/confluence/spaces/CRA/pages/2004096218/Informant+Register+Service) Confluence page (space CRA), which is the single
source of truth — do not re-create `doc/SOLUTION_BRIEF.md` or `doc/TECHNICAL_DESIGN.md`.

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
| API_CONTRACTS    | Updated | Added integration details |
| CHANGELOG        | Updated | 3 entries added          |
| ...              | ...     | ...                      |
```
