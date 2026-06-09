# Directory Structure

> How backend code is organized in this project.

---

## Overview

The backend is a conventional Spring Boot application rooted at
`src/main/java/com/codeguardian`. It uses package-by-layer organization for the
main CRUD and review flows, with feature subpackages under `service/` for AI,
RAG, caching, integration, rule templates, and authentication adapters.

Controllers are intentionally thin: they bind HTTP input, check permissions,
choose the review type or view name, and delegate business work to services.
Services contain orchestration, validation, transactions, logging, and DTO
mapping. Repositories own persistence queries.

---

## Directory Layout

```text
src/
  main/
    java/com/codeguardian/
      CodeReviewApplication.java
      config/                 Spring, Sa-Token, Redis, MinIO, AI config
      constants/              Shared constants
      controller/             MVC and REST controllers
      dto/                    API DTOs and page result wrappers
      entity/                 JPA entities for core tables
      enums/                  Integer-backed domain enums
      exception/              Global REST exception handling
      model/                  Request/config models and nested DTO namespace
      repository/             Spring Data JPA repositories
      service/                Business services and feature subpackages
      task/                   Scheduled jobs
      util/                   Small helpers and JPA converters
    resources/
      application.yml
      application-dev.yml
      application-prod.yml
      templates/              Thymeleaf pages
      templates/admin/        Admin Thymeleaf pages
      static/css/             Page styles
      static/js/              Page scripts
      rules/                  Static review rule presets
      knowledge/              Seed knowledge base data
  test/
    java/com/codeguardian/    JUnit 5 tests mirroring production packages
database/
  schema.sql                 Main PostgreSQL schema
  init_permissions.sql       Required RBAC seed data
  init_data.sql              Optional demo/test data
```

---

## Package Responsibilities

- `controller`: HTTP endpoints and page routing. Use `@RestController` for pure
  JSON APIs such as `ReviewController`, `ReportController`, `CicdController`,
  and `WebhookController`. Use `@Controller` for Thymeleaf-backed pages such as
  `AuthController`, `SettingsController`, `RoleController`, and
  `KnowledgeBaseController`; add `@ResponseBody` on JSON endpoints in those
  controllers.
- `service`: Business logic and orchestration. Existing examples include
  `ReviewService`, `UserService`, `RoleService`, `SystemConfigService`, and
  `DashboardService`.
- `service.ai`, `service.ai.impl`, `service.ai.factory`, `service.ai.tool`,
  `service.ai.tools`, `service.ai.util`, and `service.ai.output`: AI provider,
  prompt, parsing, and function-calling infrastructure.
- `service.rag`: Knowledge-base upload/search code. This package currently also
  contains the `KnowledgeDocument` JPA entity, which is a feature-local
  exception to the usual `entity/` location.
- `service.rules`: Rule engine abstractions and JSON rule templates.
- `service.cache`: Redis-backed review cache and fingerprinting.
- `service.integration`: CI/CD quality gate and Git feedback integrations.
- `entity`: Core JPA entities such as `User`, `ReviewTask`, `Finding`,
  `ReviewReport`, `Role`, `Permission`, and `SystemConfig`.
- `repository`: Spring Data JPA interfaces. Do not put repository interfaces
  beside services or controllers.
- `dto` and `model/dto`: Request/response objects. Most API DTOs are in `dto`;
  settings-specific DTOs currently live in `model/dto`.
- `util`: Small cross-cutting helpers, for example `ViewModelUtils` and
  `MapJsonConverter`.

---

## Module Organization

When adding a feature, follow the existing vertical flow:

1. Add request/response DTOs under `dto/` unless a feature already has a
   dedicated DTO namespace.
2. Add or update the `controller/` class that owns the URL family.
3. Put business logic in a `@Service` class. Keep dependencies as `private final`
   fields with `@RequiredArgsConstructor`.
4. Add JPA entities under `entity/` and repositories under `repository/` when
   persistence is needed.
5. Add Thymeleaf templates under `resources/templates` and assets under
   `resources/static` for page features.
6. Mirror the production package under `src/test/java/com/codeguardian`.

Controllers use Sa-Token permission annotations on protected actions. Examples:
`ReviewController` uses `@SaCheckPermission("REVIEW")` for review creation and
`@SaCheckPermission("QUERY")` for read endpoints; admin page controllers use
domain permissions such as `CONFIG`.

---

## Naming Conventions

- Controller classes end in `Controller`; page and REST endpoints can coexist in
  the same class only when that is already the local pattern, as in
  `AuthController` and `SettingsController`.
- Service classes end in `Service`.
- Spring Data interfaces end in `Repository`.
- DTO classes end in `DTO`; paged wrappers use explicit names such as
  `PageResult`.
- JPA entities use singular class names and plural snake_case table names:
  `User` -> `users`, `ReviewTask` -> `review_tasks`,
  `ReviewReport` -> `review_reports`.
- Domain enums end in `Enum` and expose integer values plus `fromName` /
  `fromValue` helpers.
- Test class names end in `Test` and usually describe the production class or
  behavior, for example `ReviewServiceTypeTest`,
  `SemanticFingerprintCacheServiceTest`, and `QualityGateServiceTest`.

---

## Examples

- API controller pattern: `src/main/java/com/codeguardian/controller/ReviewController.java`
- Mixed page/API controller pattern:
  `src/main/java/com/codeguardian/controller/AuthController.java`
- Service transaction and DTO mapping pattern:
  `src/main/java/com/codeguardian/service/UserService.java`
- Feature service orchestration:
  `src/main/java/com/codeguardian/service/ReviewService.java`
- Repository query pattern:
  `src/main/java/com/codeguardian/repository/ReviewTaskRepository.java`
- Feature-local RAG package:
  `src/main/java/com/codeguardian/service/rag/KnowledgeBaseService.java`
  and `src/main/java/com/codeguardian/service/rag/KnowledgeDocument.java`
