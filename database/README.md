# Database Scripts

This directory contains the PostgreSQL scripts required by CodeGuardian AI.

## Files

- `schema.sql`: creates extensions, tables, indexes, views, and helper functions.
- `init_permissions.sql`: seeds RBAC roles, permissions, and role-permission bindings.

Runtime data, database dumps, backup files, and machine-specific seed data are intentionally ignored by Git.

## Initialize A Database

Create the database first:

```bash
createdb -U <db_user> code_guardian
```

Run the schema and RBAC seed scripts:

```bash
psql -U <db_user> -d code_guardian -f database/schema.sql
psql -U <db_user> -d code_guardian -f database/init_permissions.sql
```

The schema expects PostgreSQL extensions including `pg_trgm`, `btree_gin`, `vector`, and `uuid-ossp`.
Install the required packages for your PostgreSQL distribution before running `schema.sql`.

## Administrator Account

No fixed administrator password is committed. For a private deployment, create the first administrator with your own BCrypt password hash and keep that SQL outside the public repository.

Example template:

```sql
INSERT INTO users (username, email, password_hash, real_name, status)
VALUES ('<admin_username>', '<admin_email>', '<bcrypt_hash>', '<display_name>', 0)
ON CONFLICT (username) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u, roles r
WHERE u.username = '<admin_username>' AND r.code = 'ADMIN'
ON CONFLICT (user_id, role_id) DO NOTHING;
```

Never commit real user exports, live data, or generated password hashes tied to an active environment.
