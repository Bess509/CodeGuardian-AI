-- CodeGuardian AI - RBAC seed data
-- This script creates roles, permissions, and role-permission bindings only.
-- It intentionally does not create a default administrator with a fixed password.

INSERT INTO roles (code, name, description, status)
VALUES
  ('ADMIN', '管理员', '拥有系统管理、用户管理、角色管理和配置权限', 0),
  ('REVIEWER', '审查员', '可以创建、执行和查看代码审查任务', 0),
  ('VIEWER', '查看者', '只能查看代码审查任务和报告', 0)
ON CONFLICT (code) DO NOTHING;

-- resource/action are numeric codes used by the current schema:
-- resource: TASK=1, REPORT=2, CONFIG=3, ALL=99
-- action: READ=1, CREATE=2, UPDATE=3, ALL=99
INSERT INTO permissions (code, name, description, resource, action, created_at)
VALUES
  ('QUERY', '查询权限', '查看审查任务、报告和历史记录', 1, 1, CURRENT_TIMESTAMP),
  ('REVIEW', '审查权限', '创建并执行代码审查任务', 1, 2, CURRENT_TIMESTAMP),
  ('CONFIG', '配置权限', '修改系统配置和 AI 配置', 3, 3, CURRENT_TIMESTAMP),
  ('ADMIN', '管理员权限', '拥有全部权限', 99, 99, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, created_at)
SELECT r.id, p.id, CURRENT_TIMESTAMP
FROM roles r, permissions p
WHERE r.code = 'ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, created_at)
SELECT r.id, p.id, CURRENT_TIMESTAMP
FROM roles r
JOIN permissions p ON p.code IN ('QUERY', 'REVIEW')
WHERE r.code = 'REVIEWER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, created_at)
SELECT r.id, p.id, CURRENT_TIMESTAMP
FROM roles r
JOIN permissions p ON p.code = 'QUERY'
WHERE r.code = 'VIEWER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
