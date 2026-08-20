# Service Identity

- **Service:** service-cp-crime-informant-register
- **Description:** Consumes hearing-resulted messages from a dedicated Azure Service Bus queue and produces per-authority informant register submissions (Option 2 lift-and-shift of the informant register function app)
- **Programme:** Crime Common Platform (CPP) — Modern by Default (MbD)
- **Team:** Resulting Assistant
- **Organisation:** HMCTS / Ministry of Justice

## Technology Stack

| Component      | Value                              |
|----------------|------------------------------------|
| Framework      | Spring Boot 4.0.0                  |
| Language       | Java 25+                           |
| Build tool     | Gradle (NEVER Maven)               |
| Root package   | uk.gov.hmcts.cp.informantregister     |
| Local port     | 8082                               |
| K8s port       | 4550                               |
| Database       | PostgreSQL 18-alpine (if needed)   |
| Static analysis| PMD                                |
| CI/CD          | GitHub Actions → ADO Pipeline 460  |

## Constraints

- NEVER use Maven or Spring Initializr
- NEVER scaffold from scratch — always clone from the official template
- All code in package `uk.gov.hmcts.cp.informantregister`
- Security: JWT via `io.jsonwebtoken:jjwt` + Spring Security + Entra ID
