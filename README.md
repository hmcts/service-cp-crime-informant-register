# HMCTS Crime Service Spring Boot Template

This repository provides a template for building Spring Boot applications. While the initial use case was for the HMCTS API Marketplace, the template is designed to be reusable across jurisdictions and is intended as a base paved path for wider adoption.

It includes essential configurations, dependencies, and recommended practices to help teams get started quickly.

**Note:** This template is not a framework, nor is it intended to evolve into one. It simply leverages the Spring ecosystem and proven libraries from the wider engineering community.

As HMCTS services are hosted on Azure, the included dependencies reflect this. Our aim is to stay as close to the cloud as possible in order to maximise alignment with the Shared Responsibility Model and achieve optimal security and operability.

## Want to Build Your Own Path?

That’s absolutely fine — but if you do, make sure your approach meets the following baseline requirements:

* Security – All services must meet HMCTS security standards, including vulnerability scanning and least privilege access.
* Observability – Logs, metrics, and traces must be integrated into HMCTS observability stack (e.g. Azure Monitoring).
* Audit – Systems must produce audit trails that meet legal and operational requirements.
* CI/CD Integration – Pipelines must include automated testing, deployments to multiple environments, and use approved tooling (e.g. GitHub Actions or Azure DevOps).
* Compliance & Policy Alignment – Services must align with HMCTS/MoJ policies (e.g. Coding in the Open, mandatory security practices).
* Ownership & Support – Domain teams must clearly own the service, maintain a support model, and define escalation paths.

## Implementation Patterns & Demo Project

This template is intentionally bare-bones. It provides the core Spring Boot scaffold — actuator, observability, logging — without any domain-specific or infrastructure patterns built in.

For ready-to-use implementation guides and working code examples, see the demo project:

> 🔗 **[service-hmcts-springboot-demo](https://github.com/hmcts/service-hmcts-springboot-demo)**

Each pattern lives in its own module or folder so you can browse, copy, or cherry-pick exactly what you need:

### Controllers & API
| Demo | Branch |
|---|---|
| REST controller (request/response, validation, error handling) | [`controller-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/controller-demo) |
| Exception handling | [`exception-handling-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/exception-handling-demo) |
| API versioning | [`api-versioning-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/api-versioning-demo) |
| OpenAPI client (calling downstream services) | [`openapi-client-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/openapi-client-demo) |
| API tests | [`api-test-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/api-test-demo) |

### Database / Persistence
| Demo | Branch |
|---|---|
| JPA + PostgreSQL (Spring Boot 4) | [`postgres-springboot4`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/postgres-springboot4) |
| Encrypted columns | [`postgres-encrypt-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/postgres-encrypt-demo) |
| Pessimistic locking | [`postgres-lock`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/postgres-lock) |
| Flyway Java-based migrations | [`flyway-java-migration-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/flyway-java-migration-demo) |

### Messaging
| Demo | Branch |
|---|---|
| Service Bus queue | [`servicebus-queue-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/servicebus-queue-demo) |
| Service Bus topic / subscription | [`servicebus-topic-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/servicebus-topic-demo) |
| Service Bus retry handling | [`servicebus-retry-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/servicebus-retry-demo) |

### Security & Auth
| Demo | Branch |
|---|---|
| JWT auth filter | [`jwt-token-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/jwt-token-demo) |
| Auth filter | [`auth-filter-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/auth-filter-demo) |
| Outbound HMAC auth | [`outbound-auth-hmac-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/outbound-auth-hmac-demo) |
| Entra ID (Azure AD) | [`feature/entra-auth-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/feature/entra-auth-demo) |

### Observability & Monitoring
| Demo | Branch |
|---|---|
| Actuator endpoints | [`actuator-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/actuator-demo) |
| Azure Monitor integration | [`azure-monitor-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/azure-monitor-demo) |
| Audit filter | [`audit-filter-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/audit-filter-demo) |
| Audit filter (Logback) | [`audit-filter-logback-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/audit-filter-logback-demo) |

### Azure
| Demo | Branch |
|---|---|
| Key Vault | [`azure-vault-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/azure-vault-demo) |
| APIM integration | [`apim-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/apim-demo) |
| Azurite (local Azure Storage) | [`azure-azureite-storage`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/azure-azureite-storage) |

### Other
| Demo | Branch |
|---|---|
| Clock / time abstraction (testable) | [`clock-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/clock-demo) |
| JSON mapper patterns | [`json-mapper-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/json-mapper-demo) |
| Gradle test configuration | [`gradle-test-demo`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/gradle-test-demo) |
| Misc Java patterns | [`misc-java-demos`](https://github.com/hmcts/service-hmcts-springboot-demo/tree/misc-java-demos) |

### Don't see the pattern you need?

If you've built something useful that isn't covered above — **please add it to the demo project as an exemplar**. A new module or folder per pattern keeps things easy to discover and copy. Raise a PR against [service-hmcts-springboot-demo](https://github.com/hmcts/service-hmcts-springboot-demo) with your working example and a short README explaining the pattern.

---

## Documentation

Further documentation can be found in the [docs](docs) directory.

### Key Documentation
- [Spring Boot v4 Upgrade Guide](docs/SpringUpgradev4.md) - Details on the Spring Boot v4 upgrade, tracing test fixes, and code refactoring improvements
- [Logging Documentation](docs/Logging.md) - Logging configuration and best practices
- [Pipeline Documentation](docs/PIPELINE.md) - CI/CD pipeline configuration and deployment processes

### Prerequisites

- ☕️ **Java 25 or later**: Ensure Java is installed and available on your `PATH`.
- ⚙️ **Gradle**: [Install Gradle](https://gradle.org/install/). The project itself defines which Gradle version to use (gradle/wraper/gradle-wrapper.properties).

You can verify installation with:
```bash
java -version
gradle -v
```

## Installation

### Build
```bash
gradle build
```

`build` will run all tests.

### Tests
- `gradle test` for running unit and integration tests

## Static code analysis

Install PMD

```bash
brew install pmd
```
```bash
pmd check \
    --dir src/main/java \
    --rulesets \
    .github/pmd-ruleset.xml \
    --format html \
    -r build/reports/pmd/pmd-report.html
```

Run PMD from Gradle

```
gradle pmdTest
```

### Contribute to This Repository

Contributions are welcome! Please see the [CONTRIBUTING.md](.github/CONTRIBUTING.md) file for guidelines.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details
