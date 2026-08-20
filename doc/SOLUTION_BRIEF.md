# Solution Brief — service-cp-crime-informant-register

## Overview

| Field            | Value                                    |
|------------------|------------------------------------------|
| Service Name     | service-cp-crime-informant-register                           |
| Team             | Resulting Assistant                              |
| Programme        | Crime Common Platform (CPP) — MbD        |
| Status           | Draft                                    |

## Problem Statement

<!-- What problem does this service solve? Who is affected? -->

## Proposed Solution

<!-- High-level description of the service and its role in the platform -->

## Key Integrations

<!-- What other services/systems does this interact with? -->

| System           | Direction  | Protocol | Purpose              |
|------------------|------------|----------|----------------------|
|                  | Inbound    |          |                      |
|                  | Outbound   |          |                      |

## Domain Model

Entities: DistributionCommand, ProcessedRequest, ProcessedOutput, RegisterFragment, InformantRegisterDocument

<!-- Brief description of the core domain objects -->

## API Surface

None — this service exposes no REST API (actuator health/metrics only); its inbound contract is the ASB queue message, its outbound contract is the results add-informant-register command

## Non-Functional Requirements

| Requirement      | Target                                   |
|------------------|------------------------------------------|
| Availability     | 99.9%                                    |
| Response time    | < 200ms (p95)                            |
| Throughput       | TBD                                      |
| Data retention   | TBD                                      |

## Risks & Assumptions

<!-- Key risks and assumptions that affect the design -->

## Out of Scope (for MVP)

<!-- What is explicitly deferred? -->
