# Backend Development Guidelines

> Project-specific backend conventions for CodeGuardian AI.

---

## Overview

This is a Java 21, Spring Boot 3.4.x backend using Maven, Spring MVC,
Thymeleaf pages, Spring Data JPA, PostgreSQL, Redis, Sa-Token, Lombok,
Jackson, OkHttp, JavaParser, Spring AI, MinIO, and JUnit 5/Mockito tests.

These guidelines document what the current codebase actually does. Match these
patterns when adding backend code so future Trellis implement/check agents stay
consistent with the project.

---

## Pre-Development Checklist

- Read this index and the specific guideline files for the layer you are about
  to edit.
- Put controllers, services, DTOs, entities, repositories, enums, and utilities
  in the established `com.codeguardian` packages.
- Use constructor injection through Lombok `@RequiredArgsConstructor` and `final`
  dependencies for Spring beans.
- Keep database access in Spring Data repositories and transaction boundaries in
  services.
- Add or update focused JUnit 5 tests for the behavior being changed.
- If a persistent model changes, update both the JPA entity and the SQL scripts
  under `database/`.

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | Package organization, file layout, naming | Filled |
| [Database Guidelines](./database-guidelines.md) | JPA, PostgreSQL scripts, queries, transactions | Filled |
| [Error Handling](./error-handling.md) | Exception flow, API error bodies, validation | Filled |
| [Quality Guidelines](./quality-guidelines.md) | Code standards, tests, review checklist | Filled |
| [Logging Guidelines](./logging-guidelines.md) | Lombok logging, levels, sensitive data rules | Filled |

---

## Quality Check

- Run `mvn test` before finishing when the local database and external tool
  prerequisites are available.
- For narrow changes, run a targeted command such as
  `mvn -Dtest=QualityGateServiceTest test` or the nearest affected test class.
- Review `git diff` for accidental formatting churn, generated files, secrets,
  and changes outside the task scope.
- Keep Trellis specs in English even though many source comments and UI strings
  are Chinese.
