# Logging Guidelines

> How logging is done in this project.

---

## Overview

The project uses SLF4J/Logback through Lombok `@Slf4j`. Spring Boot provides
the logging backend. Most controllers, services, configuration classes,
scheduled jobs, AI providers, tools, and parsers that log are annotated with
`@Slf4j`.

Use parameterized log messages with `{}` placeholders. Do not concatenate large
strings just to log them.

Environment defaults:

- `application-dev.yml`: `com.codeguardian` logs at `DEBUG`.
- `application-prod.yml`: root and `com.codeguardian` logs at `INFO`.
- `application.yml`: JPA SQL logging is currently enabled with
  `spring.jpa.show-sql: true` and formatted SQL.

---

## Log Levels

- `debug`: Detailed internals useful for local diagnosis. Existing examples
  include request construction and response parsing in AI providers,
  prompt/response parsing helpers, Semgrep temp-file paths, vector-search
  fallback details, and ChatClient creation.
- `info`: Normal lifecycle events, successful writes, started/completed work,
  selected providers, counts, timings, and cache/index updates. Examples:
  `ReviewService.createReviewTask`, `AIModelService.reviewCode`,
  `UserService.createUser`, `RoleService.assignPermissions`,
  `KnowledgeBaseService.uploadDocument`, and `DashboardScheduler`.
- `warn`: Recoverable or expected degraded paths. Examples: AI disabled, no
  configured provider, missing knowledge file, Redis warm-up failure, Semgrep
  zero-output warnings, schema fix skipped, vector search fallback, and missing
  PDF font.
- `error`: Failed operations that need investigation or produce failed client
  responses. Include the exception object where available. Examples:
  `GlobalExceptionHandler`, `ReportController`, `ReviewController` Git/file
  endpoints, `AbstractAIModelProvider`, `CodeParserService`, and MinIO upload
  failures.

---

## Structured Logging

There is no JSON logging framework configured. Use consistent key/value style in
messages:

```java
log.info("Created user: username={}, id={}", user.getUsername(), user.getId());
log.error("Generate report failed: taskId={}", taskId, e);
```

Prefer identifiers, counts, provider names, status codes, elapsed milliseconds,
and sizes over whole object dumps. Existing code often records `taskId`,
`username`, `provider`, `model`, `status`, `responseTime`, `contentLength`,
`findingsCount`, `bucket`, and `object`.

---

## What To Log

- Review task creation, async execution, completion, failure, finding counts,
  and executor shutdown (`ReviewService`).
- Authentication attempts, login success/failure reasons, and session creation
  without passwords (`AuthService`, `AuthController`).
- User/role/config write operations with the affected id/code.
- External operations: Git clone/read, AI provider calls, Semgrep execution,
  Redis warm-up/cache writes, MinIO uploads/downloads/deletes, and report/PDF
  generation.
- Fallbacks and degraded behavior: missing AI provider, missing knowledge file,
  vector-search fallback, missing fonts, parser failures, or optional tool
  absence.
- Validation and authorization failures in the global exception handler.

---

## What Not To Log

- Passwords, password hashes, Sa-Token tokens, API keys, Git credentials, Redis
  passwords, MinIO secret keys, or Authorization headers.
- Full request bodies for authentication, settings that may contain secrets, or
  webhook payloads unless explicitly redacted.
- Full source code, full prompts, full AI responses, or full HTTP response bodies
  in new production-path `info` logs. Existing AI classes are very verbose for
  debugging; keep any new raw-payload logging at `debug` and avoid secrets.
- User PII beyond the minimum identifier needed for diagnosis.
- Repeated high-volume per-item logs inside loops unless guarded by `debug` or
  summarized with counts.

---

## Common Mistakes To Avoid

- Do not use `System.out.println` in production code. It appears only in a few
  tests and integration diagnostics.
- Do not log and swallow an exception unless the method is intentionally
  best-effort and returns a safe default.
- Do not log the same failure at several layers unless each layer adds useful
  context.
- Do not omit the exception object on `error` logs when a stack trace is needed.
