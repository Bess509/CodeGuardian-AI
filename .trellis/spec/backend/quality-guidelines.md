# Quality Guidelines

> Code quality standards for backend development.

---

## Overview

The backend is a Maven-built Java 21 Spring Boot application. The codebase uses
Lombok heavily, especially `@Data`, `@Builder`, `@NoArgsConstructor`,
`@AllArgsConstructor`, `@RequiredArgsConstructor`, and `@Slf4j`. Tests use
JUnit 5, Mockito, and targeted Spring Boot integration tests.

Favor small, behavior-focused changes that preserve the existing package
layout, controller/service/repository split, transaction boundaries, and DTO
contracts.

---

## Required Patterns

- Use constructor injection for Spring beans via `@RequiredArgsConstructor` and
  `private final` fields.
- Keep controllers thin. Controllers bind input, set request-specific flags,
  check permissions, call services, map simple responses, and return view names
  or `ResponseEntity`.
- Put business logic, validation, transaction boundaries, async orchestration,
  and entity-to-DTO conversion in services.
- Use Spring Data repositories for database access.
- Use `@Transactional(readOnly = true)` on read service methods and
  `@Transactional` on write methods.
- Protect non-public endpoints with Sa-Token annotations such as
  `@SaCheckPermission("REVIEW")`, `QUERY`, `CONFIG`, or the matching domain
  permission.
- Convert integer-backed enum values with `ReviewTypeEnum`, `SeverityEnum`,
  `TaskStatusEnum`, and `ModelProviderEnum` helpers instead of scattering magic
  conversions.
- Return DTOs for APIs. Do not expose JPA entities from new public API endpoints
  unless an existing controller in the same area already does so.
- For async review behavior, preserve the post-commit dispatch pattern in
  `ReviewService.createReviewTask`.

---

## Forbidden Patterns

- Do not introduce field injection (`@Autowired` on mutable fields) in
  production code.
- Do not put SQL/query construction in controllers. Use repositories and, when
  needed, service-level Specifications.
- Do not bypass the service layer from page templates, JavaScript, or unrelated
  controllers.
- Do not add unpermissioned admin/review/query endpoints.
- Do not store plaintext passwords or compare passwords manually. Existing auth
  uses `BCryptPasswordEncoder`.
- Do not add production `System.out.println`; use `log`.
- Do not add raw secrets or credentials to logs, configs, tests, or example
  data.
- Do not change persistent fields without updating database scripts and tests.

---

## Testing Requirements

Add focused tests for changed behavior:

- Pure/service logic: use JUnit 5 with Mockito when dependencies can be mocked.
  Examples: `AIModelServiceTest`, `ReviewServiceTypeTest`,
  `ReviewServiceSemanticCacheHitTest`, and
  `SemanticFingerprintCacheServiceTest`.
- Small deterministic domain logic: instantiate the class directly when no
  Spring context is needed. Example: `QualityGateServiceTest`.
- Page controller model behavior: use mocked dependencies and
  `ExtendedModelMap`, as in `ReviewPageControllerTest`.
- Persistence/auth flows that need Spring wiring: use `@SpringBootTest` and
  `@Transactional`, as in `AuthServiceTest`.
- External tool integration tests should handle missing local tools gracefully.
  The Semgrep integration tests print skip diagnostics when Semgrep is not
  installed.

Preferred validation command:

```bash
mvn test
```

For narrow edits, run the nearest affected tests first:

```bash
mvn -Dtest=QualityGateServiceTest test
mvn -Dtest=ReviewServiceTypeTest test
```

If the full suite requires local PostgreSQL, Redis, Semgrep, or AI/provider
configuration that is not available, record exactly which targeted tests ran
and why the full command could not be completed.

---

## Code Review Checklist

- Does the change keep the established package and layer boundaries?
- Are new endpoints permission-protected and validated?
- Are service methods transactional where database writes or read consistency
  require it?
- Are repository queries paginated or indexed when they can grow large?
- Are enum integer values converted through the enum helpers?
- Are entity changes reflected in `database/schema.sql` and seed scripts when
  needed?
- Are errors returned using the global handler or an existing feature-specific
  DTO contract?
- Are logs useful, parameterized, and free of secrets?
- Are tests focused on the behavior changed, including failure/edge cases?
- Did `mvn test` or the relevant targeted Maven test command run?

---

## Examples

- Constructor injection and service transaction boundaries:
  `src/main/java/com/codeguardian/service/UserService.java`
- Async review orchestration and post-commit dispatch:
  `src/main/java/com/codeguardian/service/ReviewService.java`
- Permission-protected API endpoints:
  `src/main/java/com/codeguardian/controller/ReviewController.java`
- Settings validation:
  `src/main/java/com/codeguardian/service/SystemConfigService.java`
- Mockito unit test:
  `src/test/java/com/codeguardian/service/AIModelServiceTest.java`
- Spring Boot transactional integration test:
  `src/test/java/com/codeguardian/service/AuthServiceTest.java`
