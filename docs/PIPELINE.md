# Pipeline Overview

## Current Pipeline

This diagram summarises the flow up to publishing an image to Azure Container Registry (ACR).

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant Repo as (Micro)Service Repository
    participant GH as GitHub Actions
    participant ADO as Azure DevOps
    participant JFA as JFrog Artefactory
    participant Art as Azure Artifacts (Public)
    participant ACR as Azure Container Registry

    Dev->>Repo: Pull Request
    Repo->>GH: Various Actions Workflows are triggered
    note right of GH: Actions Workflows are run in parallel
    GH->>GH: Security Scans (CodeQL, Trufflehog, Gitleaks)
    GH->>GH: Linting & Static Code Analysis
    GH->>GH: Unit & Integration Tests
    GH->>GH: Build

    note right of GH: All assurance workflow steps are complete
    GH->>Art: Publish artefact [DRAFT] (versioned via git tag & commit SHA)
    GH->>ADO: Trigger ADO pipeline (hmcts/trigger-ado-pipeline)

    ADO->>JFA: Pull libraries from Azure Artefact
    JFA->>Art: Upstream dependency
    ADO->>ACR: Build & publish container

```

## Proposed Pipeline

This represents the next iteration of the pipeline, simplifying the process by removing Azure DevOps and JFrog Artifactory, 
but more importantly allow the owners of the repository to have full control of the pipeline and publishing the actual artefact that they produce and is used.

The GitHub Actions workflow will handle building and publishing both the application artefact and the Docker image to Azure Container Registry (ACR).

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant Repo as (Micro)Service Repository
    participant GH as GitHub Actions
    participant Art as Azure Artifacts (Public)
    participant ACR as Azure Container Registry

    Dev->>Repo: Pull Request
    Repo->>GH: Various Actions Workflows are triggered
    note right of GH: Actions Workflows are run in parallel
    GH->>GH: Security Scans (CodeQL, Trufflehog, Gitleaks)
    GH->>GH: Linting & Static Code Analysis
    GH->>GH: Unit & Integration Tests
    GH->>GH: Build

    note right of GH: All assurance workflow steps are complete
    GH->>Art: Publish artefact [DRAFT] (versioned via git tag & commit SHA)
    note right of GH: The build and publish of the image is one step via the<br/>custom action "build-push-action" but is represented<br/>here as two steps for readability
    GH->>GH: Build Docker image (versioned via git tag & commit SHA)
    GH->>ACR: Publish container
```
