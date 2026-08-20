# Spring Boot v4 Upgrade Documentation

## Overview

This document outlines the changes made during the upgrade to Spring Boot v4.0.0-M3, focusing on the resolution of tracing integration test failures and code refactoring improvements.

## Key Changes

### 1. Spring Boot Version Upgrade

#### Core Upgrade
- **Spring Boot version**: Upgraded from `3.5.6` to `4.0.0-M3`
- **SpringDoc OpenAPI**: Updated from `2.8.13` to `3.0.0-M1` for Spring Boot v4 compatibility
- **Repository configuration**: Added Spring milestone repository for Boot 4 milestones

#### Build Configuration Changes
- Updated Gradle build script formatting and indentation
- Added Spring milestone repository: `maven { url = "https://repo.spring.io/milestone" }`
- Updated CycloneDX configuration to exclude additional test configurations
- Enhanced JAR creation with better CHANGELOG.md handling

### 2. Dependency Updates and Cleanup

#### Removed Dependencies
- **Jackson Databind**: Removed explicit version (`2.20.0`) to use Spring Boot managed version
- **Micrometer Tracing BOM**: Removed platform BOM dependency
- **Spring Boot Starter AOP**: Replaced with `spring-boot-starter-aspectj`
- **Spring Boot Starter JSON**: Removed (now included by default)

#### Updated Dependencies
- **SpringDoc OpenAPI**: `2.8.13` → `3.0.0-M1`
- **Log4J dependencies**: Removed explicit versions to use Spring Boot managed versions
- **Spring Boot Test**: Updated to use Spring Boot managed version without explicit versioning

#### New Dependencies
- **Micrometer Tracing Test**: Added `io.micrometer:micrometer-tracing-test` for integration tests
- **Lombok integration test support**: Added `apiTestCompileOnly` and `apiTestAnnotationProcessor`

### 3. Test Infrastructure Improvements

#### New Test Configuration Classes
- **`BaseIntegrationTest`**: Created base class for integration tests with proper configuration
- **`IntegrationTestConfiguration`**: Excludes OpenTelemetry tracing auto-configuration
- **`TracingIntegrationTestConfiguration`**: Provides SimpleTracer and ObservationRegistry for tracing tests

#### Test Class Updates
- **`CourtScheduleControllerIntegrationTest`**: Now extends `BaseIntegrationTest`
- **`HealthCheckIntegrationTest`**: Now extends `BaseIntegrationTest`
- **`JWTFilterDisabledIntegrationTest`**: Now extends `BaseIntegrationTest`
- **`JWTFilterIntegrationTest`**: Now extends `BaseIntegrationTest`
- **`SpringLoggingIntegrationTest`**: Moved from `src/test` to `src/integrationTest` and extends `BaseIntegrationTest`

#### Remove all references to Pact

### 4. Tracing Integration Test Complete Rewrite

#### Problem
The original `TracingIntegrationTest` was failing due to configuration conflicts between the OpenTelemetry tracing auto-configuration and the test requirements.

#### Root Cause
- The `IntegrationTestConfiguration` was excluding OpenTelemetry tracing auto-configuration
- Tests expected `traceId` and `spanId` to be present in JSON log output
- Tracing wasn't properly configured to populate the MDC (Mapped Diagnostic Context)
- Original test was located in `src/test` instead of `src/integrationTest`

#### Solution
1. **Completely rewrote the test** from scratch with proper Spring Boot v4 compatibility
2. **Created separate test configuration** (`TracingIntegrationTestConfiguration`) specifically for tracing tests
3. **Manually populated MDC** with trace information in test setup, similar to how `TracingFilter` works
4. **Updated test logic** to handle multiple log lines and find specific log messages from `RootController`
5. **Applied comprehensive refactoring** to eliminate code duplication
6. **Moved test to proper location** (`src/integrationTest`)

#### Files Modified
- `src/integrationTest/java/uk/gov/hmcts/cp/testconfig/TracingIntegrationTestConfiguration.java` (new)
- `src/integrationTest/java/uk/gov/hmcts/cp/logging/TracingIntegrationTest.java` (rewritten)
- `src/integrationTest/java/uk/gov/hmcts/cp/controllers/HealthCheckIntegrationTest.java`
- `src/integrationTest/java/uk/gov/hmcts/cp/controllers/CourtScheduleControllerIntegrationTest.java`

### 5. SpringLoggingIntegrationTest Migration and Updates

#### Changes Made
- **Moved from `src/test` to `src/integrationTest`**: Proper test location for integration tests
- **Updated to extend `BaseIntegrationTest`**: Consistent configuration with other integration tests
- **Updated imports**: Reorganized imports for better readability
- **Fixed timestamp field assertion**: Changed from `timestamp` to `@timestamp` to match actual log output
- **Added debug output**: Added console output for troubleshooting log format

#### Key Updates
```java
// Before:
@SpringBootTest
public class SpringLoggingIntegrationTest {

// After: 
public class SpringLoggingIntegrationTest extends BaseIntegrationTest {
```

## Testing

### Verification Steps
- All integration tests pass: `./gradlew integration`

## Migration Notes

### For Developers
- When adding new tracing tests, use the `TracingIntegrationTestConfiguration`
- For other integration tests, extend `BaseIntegrationTest` to ensure proper configuration

### Configuration Changes
- OpenTelemetry auto-configuration is excluded for integration tests
- Tracing tests use `SimpleTracer` for consistent behavior
- MDC is manually populated in tests to simulate real-world tracing behavior

## Dependencies

### Updated Dependencies
- Spring Boot upgraded to v4.0.0-M3
- All related Spring dependencies updated accordingly
- Micrometer tracing dependencies maintained for compatibility
