# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

The project uses PostgreSQL as the primary database and Spring Data JPA for
application persistence. Redis is used for Sa-Token/session integration and
review fingerprint caching. PostgreSQL scripts under `database/` are part of
the source of truth and must stay aligned with JPA entities.

Configuration is environment-specific:

- `application.yml` and `application-dev.yml` use PostgreSQL defaults from
  environment variables and currently set `spring.jpa.hibernate.ddl-auto:
  update`.
- `application-prod.yml` sets `ddl-auto: validate`, so production expects SQL
  scripts/schema to already match the entities.
- PostgreSQL extensions in the schema include `pg_trgm`, `btree_gin`, and
  `vector`.

---

## Entities

JPA entities use Lombok and identity primary keys:

```java
@Entity
@Table(name = "review_tasks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}
```

Use `LocalDateTime` for timestamps. Set defaults with `@PrePersist` and
`@PreUpdate` when the entity owns the timestamp behavior. Examples:

- `User` sets `createdAt`, default `status`, and `updatedAt`.
- `ReviewTask` sets default `createdAt` and `TaskStatusEnum.PENDING`.
- `ReviewReport` sets default `createdAt`.
- `KnowledgeDocument` sets `createTime`.

The current entities usually store enum values as integers, not
`@Enumerated`. Use the helper methods in `ReviewTypeEnum`, `SeverityEnum`, and
`TaskStatusEnum` when converting between API strings, integer database values,
and display names.

For JSON-like maps, use a converter only where the current code does so:
`KnowledgeDocument.metadata` uses `@Convert(converter = MapJsonConverter.class)`.
Some existing JSON payloads are plain text columns, such as
`ReviewReport.statistics`.

---

## Query Patterns

Put persistence access in `repository/` interfaces:

- Extend `JpaRepository<Entity, IdType>` for standard CRUD.
- Add `JpaSpecificationExecutor<T>` when services build Criteria
  `Specification`s, as `UserRepository` does for user search.
- Prefer Spring Data derived queries for simple lookups:
  `findByUsername`, `existsByEmail`, `findByTaskIdAndSeverity`,
  `findTop5ByStatusOrderByCreatedAtDesc`.
- Use JPQL `@Query` for joins, counts, null-aware filtering, ordering, or
  grouped/latest records. Examples:
  `ReviewTaskRepository.findByConditions`,
  `UserRoleRepository.findRoleCodesByUserId`,
  `RolePermissionRepository.findPermissionCodesByRoleId`, and
  `KnowledgeDocumentRepository.findAllNullsLast`.
- Use `Page`, `Pageable`, `PageRequest`, and `Sort` for paginated API results.
  Controllers build request-level paging; repositories execute it.

Do not build ad hoc SQL strings in controllers or services when a repository
method, Specification, or JPQL query fits the local pattern.

---

## Transactions

Transactions live at the service layer:

- Mark read methods with `@Transactional(readOnly = true)`.
  Examples: `UserService.queryUsers`, `RoleService.getAllRoles`,
  `PermissionService.getAllPermissions`, and `DashboardService.getMetrics`.
- Mark write methods with `@Transactional`.
  Examples: `UserService.createUser`, `UserService.updateUser`,
  `RoleService.assignPermissions`, `SystemConfigService.saveSettings`, and
  `ReviewService.createReviewTask`.
- Keep multi-step writes in one service method when deleting/replacing join rows
  plus saving new rows. `UserService.assignRoles` and
  `RoleService.assignPermissions` first delete existing relations, then insert
  replacement rows inside the same transaction.
- For work that must run after the task row is committed, follow
  `ReviewService.createReviewTask`: register a
  `TransactionSynchronization.afterCommit()` callback before dispatching async
  review work.

---

## Migrations And Scripts

There is no Flyway or Liquibase setup. Database changes are managed with SQL
scripts:

- `database/schema.sql`: main schema, constraints, indexes, triggers, views, and
  helper functions.
- `database/init_permissions.sql`: required RBAC seed data and default admin.
- `database/init_data.sql`: optional demo/test data.
- `database/20260315/*.sql`: dated snapshots of schema and seed data.
- `database/code_guardian_full_dump.sql`: full dump snapshot.

When adding or changing persistent fields:

1. Update the JPA entity.
2. Update `database/schema.sql` with matching table/column/constraint/index
   definitions.
3. Update seed scripts if default roles, permissions, categories, or demo data
   change.
4. Preserve production compatibility with `application-prod.yml`, where
   Hibernate validates rather than creates schema.

---

## Naming Conventions

- Tables are plural snake_case: `users`, `review_tasks`, `review_reports`,
  `system_configs`, `knowledge_documents`, `user_roles`.
- Java fields are camelCase; use `@Column(name = "...")` when the database name
  cannot be inferred clearly, for example `Finding.taskId` -> `task_id`.
- Foreign key columns end in `_id`: `task_id`, `user_id`, `role_id`,
  `permission_id`.
- Indexes follow `idx_<table>_<column-or-purpose>`, for example
  `idx_review_tasks_status_created_at`, `idx_findings_task_severity`, and
  `idx_users_username_gin`.
- Constraints use readable prefixes such as `fk_`, `uk_`, and `chk_`.
- Text-heavy fields use `TEXT` via `columnDefinition = "TEXT"` or SQL script
  column definitions.

---

## Common Mistakes To Avoid

- Do not rely only on `ddl-auto: update`; production uses `validate`, and the
  SQL scripts must be kept current.
- Do not store new domain enum names in the database unless you intentionally
  migrate the existing integer-value convention.
- Do not add repository methods directly to services or controllers. Put query
  methods in `repository/`.
- Do not forget indexes for new filtered/sorted fields. Existing history and
  dashboard flows rely on status, type, created_at, severity, category, and GIN
  indexes.
- Do not delete parent rows without checking join/child cleanup behavior. The
  schema uses `ON DELETE CASCADE` for many relation tables, while services also
  explicitly delete join rows in role/user management.
