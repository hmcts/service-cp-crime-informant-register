# Parity Deviations Register

Behaviour-parity is the rule (constitution Principle I): the observable behaviour of this
service is defined by the legacy informant register function app plus the golden fixtures.
Every deliberate divergence from legacy behaviour MUST be recorded here, with review sign-off,
before the code that introduces it merges. The golden-file harness fails on any divergence
not listed in this register.

| # | Deviation | Legacy behaviour | New behaviour | Rationale | Approved |
|---|---|---|---|---|---|
| 1 | Verified TLS on the payload fetch | Redis client connects with `rejectUnauthorized: false` (certificate checks disabled) | TLS certificate verification enabled, platform CA bundle | Security defect (D14 in the design defect register); not observable in register output | Design sign-off 19 Aug 2026 (Option 2 page: the only improvements in this delivery are transport-level) |
| 2 | Transport reliability | Final POST errors swallowed at three levels; no retry; hearing silently lost | Failures retry (broker redelivery), then dead-letter with alerting; POST client retries connect/IO/5xx/429 | The single agreed improvement of the lift-and-shift (Option 2 page, "Scope"); changes failure behaviour only, never successful-path output | Design sign-off 19 Aug 2026 |

Entries below this line are added per change, newest first. Renumber never; append only.
