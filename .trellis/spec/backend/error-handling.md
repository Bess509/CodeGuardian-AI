# Error Handling

> How errors are handled in this project.

---

## Overview

Most API errors are centralized in
`src/main/java/com/codeguardian/exception/GlobalExceptionHandler.java`, which is
annotated with `@RestControllerAdvice`. Controllers return `ResponseEntity`
for JSON endpoints. Services usually throw unchecked exceptions and let the
global handler translate them, except for specialized endpoints that return a
typed failure DTO.

Request DTO validation uses Jakarta Validation annotations and controller
parameters annotated with `@Valid`.

---

## Error Types

The current codebase uses a small set of exception categories:

- `IllegalArgumentException` for invalid user input or unsupported request
  shape. Examples: empty code content in `ReviewService`, invalid settings in
  `SystemConfigService`, invalid server file path in `ReviewController`.
- `RuntimeException` for business failures such as missing users/roles/tasks,
  duplicate usernames/emails/roles, Git clone/read failures, MinIO failures,
  and tool execution failures.
- `AIModelException` for AI-provider failures. It extends `RuntimeException`
  and carries provider/status context for provider implementations.
- Sa-Token exceptions (`NotLoginException`, `NotPermissionException`) for
  authentication and authorization failures.
- `MethodArgumentNotValidException` for `@Valid` request body/model validation.
- `MaxUploadSizeExceededException` and `NoHandlerFoundException` for web-layer
  infrastructure cases.

There is no broad hierarchy of custom business exceptions today. Keep new
custom exceptions small and justified; match the existing unchecked flow unless
a feature truly needs typed handling.

---

## Error Handling Patterns

Use service-level validation and throw exceptions close to the violated rule:

```java
if (dto.getMaxIssues() != null && dto.getMaxIssues() < 1) {
    throw new IllegalArgumentException("...");
}
```

Use repository `orElseThrow` for not-found checks, as in `UserService`,
`RoleService`, and `ReviewService.getReviewTask`.

For normal CRUD/API endpoints, do not wrap every service call in a local
`try/catch`; let `GlobalExceptionHandler` produce the response. Examples:
`ReviewController.reviewSnippet`, `UserController.createUser`, and
`RoleController.createRole`.

Use local `try/catch` when the endpoint has a feature-specific response DTO or
binary/download response:

- `ReviewController.cloneGitRepository`, `readGitFile`, `getServerFileList`,
  and `readServerFile` return `GitCloneResponseDTO` / `GitFileResponseDTO` with
  `success=false`.
- `SettingsController.saveSettings` and `importSettings` return
  `OperationResponseDTO.error(...)`.
- `KnowledgeBaseController.download` returns `notFound()` or
  `internalServerError().build()`.
- `ReportController` catches report/PDF generation failures to return status
  codes appropriate to the content response.

When catching, log the failure and include the exception object for stack traces
on real failures:

```java
log.error("Failed to save settings", e);
return ResponseEntity.badRequest().body(OperationResponseDTO.error(e.getMessage()));
```

---

## API Error Responses

The global handler returns JSON maps for most API errors:

```json
{
  "error": "...",
  "message": "...",
  "status": 400
}
```

Validation errors use an `errors` object keyed by field name:

```json
{
  "error": "Validation failed",
  "errors": {
    "username": "..."
  },
  "status": 400
}
```

Authentication behavior is request-aware:

- If the request appears to want JSON (`Accept: application/json`, AJAX header,
  or URI beginning with `/api`), `NotLoginException` returns HTTP 401 JSON.
- Otherwise, it returns HTTP 302 with `Location: /login`.
- `NotPermissionException` returns HTTP 403 JSON.

Feature-specific DTO responses do not use the map shape. They use the local DTO
contract, for example `OperationResponseDTO` with `success`, `message`, and
optional `data`, or Git response DTOs with `success` and `error`.

---

## Validation

Request DTOs use annotations such as `@NotBlank` in:

- `LoginRequestDTO`
- `ReviewRequestDTO`
- `UserCreateDTO`
- `RoleCreateDTO`

Controllers apply `@Valid` to `@RequestBody` or `@ModelAttribute` parameters.
For Thymeleaf form submissions, use `BindingResult` immediately after the
validated model object and return the page with an error model attribute, as
`AuthController.login` does.

---

## Common Mistakes To Avoid

- Do not return raw stack traces to clients. Log stack traces server-side and
  return the established map or DTO error body.
- Do not catch exceptions only to return `null` or silently continue, unless the
  existing method is explicitly a best-effort parser/default helper.
- Do not expose credentials, API keys, Git passwords, or tokens in error
  messages.
- Do not use a local `try/catch` around simple service calls if the global
  handler already covers the expected exception type.
- Do not add unvalidated request fields to service logic. Add Jakarta
  Validation annotations or explicit service validation.
