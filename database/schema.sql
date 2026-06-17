-- ============================================
-- CodeGuardian AI - PostgreSQL 数据库建表脚本
-- 版本: v2.0.0
-- 日期: 2025-12-04
-- 更新: 2025-12-04（优化字段类型，使用TIMESTAMPTZ和更合理的类型）
-- ============================================

-- 连接到数据库（需要在外部执行）
-- \c code_guardian;

-- ============================================
-- 1. 启用扩展
-- ============================================
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gin;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================
-- 2. 创建表
-- ============================================

-- 2.1 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash CHAR(60) NOT NULL,
    real_name VARCHAR(64),
    phone VARCHAR(16),
    avatar_url TEXT,
    status SMALLINT NOT NULL DEFAULT 0,
    last_login_at TIMESTAMPTZ,
    last_login_ip INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    metadata JSONB,
    
    CONSTRAINT chk_users_status 
        CHECK (status IN (0, 1, 2)),
    CONSTRAINT chk_users_email 
        CHECK (email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

-- 2.2 角色表
CREATE TABLE IF NOT EXISTS roles (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL,
    description TEXT,
    status SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_roles_status 
        CHECK (status IN (0, 1))
);

-- 2.3 权限表
CREATE TABLE IF NOT EXISTS permissions (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL,
    description TEXT,
    resource SMALLINT,
    action SMALLINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2.4 用户角色关联表
CREATE TABLE IF NOT EXISTS user_roles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_user_roles_user_id 
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role_id 
        FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
    CONSTRAINT uk_user_roles_unique UNIQUE (user_id, role_id)
);

-- 2.5 角色权限关联表
CREATE TABLE IF NOT EXISTS role_permissions (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_role_permissions_role_id 
        FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission_id 
        FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE,
    CONSTRAINT uk_role_permissions_unique UNIQUE (role_id, permission_id)
);

-- 2.6 审查任务表
CREATE TABLE IF NOT EXISTS review_tasks (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    review_type SMALLINT NOT NULL,
    scope TEXT,
    status SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    metadata JSONB,
    
    CONSTRAINT fk_review_tasks_user_id 
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_review_tasks_status 
        CHECK (status IN (0, 1, 2, 3, 4)),
    CONSTRAINT chk_review_tasks_type 
        CHECK (review_type IN (0, 1, 2, 3, 4)),
    CONSTRAINT chk_review_tasks_time 
        CHECK (completed_at IS NULL OR completed_at >= created_at)
);

-- 2.7 审查发现表
CREATE TABLE IF NOT EXISTS findings (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    severity SMALLINT NOT NULL,
    title TEXT NOT NULL,
    location TEXT NOT NULL,
    start_line INTEGER,
    end_line INTEGER,
    description TEXT NOT NULL,
    suggestion TEXT,
    diff TEXT,
    category VARCHAR(32),
    source VARCHAR(255),
    rule_id BIGINT,
    confidence DECIMAL(3,2),
    grounded BOOLEAN NOT NULL DEFAULT FALSE,
    evidence_count INTEGER NOT NULL DEFAULT 0,
    evidence_hash CHAR(64),
    grounding_summary TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- 外键约束
    CONSTRAINT fk_findings_task_id 
        FOREIGN KEY (task_id) REFERENCES review_tasks(id) ON DELETE CASCADE,
    
    -- 检查约束
    CONSTRAINT chk_findings_severity 
        CHECK (severity IN (0, 1, 2, 3)),
    CONSTRAINT chk_findings_category 
        CHECK (category IS NULL OR category IN ('SECURITY','PERFORMANCE','BUG','CODE_STYLE','MAINTAINABILITY')),
    CONSTRAINT chk_findings_line 
        CHECK ((start_line IS NULL AND end_line IS NULL) OR 
               (start_line IS NOT NULL AND end_line IS NOT NULL AND end_line >= start_line)),
    CONSTRAINT chk_findings_confidence 
        CHECK (confidence IS NULL OR (confidence >= 0.00 AND confidence <= 1.00))
);

-- 2.8 审查报告表
CREATE TABLE IF NOT EXISTS review_reports (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL UNIQUE,
    html_content TEXT,
    markdown_content TEXT,
    statistics JSONB,
    pdf_path TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    
    -- 外键约束
    CONSTRAINT fk_review_reports_task_id 
        FOREIGN KEY (task_id) REFERENCES review_tasks(id) ON DELETE CASCADE
);

-- 2.10 Review evidence table for grounded findings and task-level context.
CREATE TABLE IF NOT EXISTS review_evidence (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    finding_id BIGINT,
    evidence_type VARCHAR(48) NOT NULL,
    source_name VARCHAR(128),
    source_ref TEXT,
    locator VARCHAR(255),
    start_line INTEGER,
    end_line INTEGER,
    excerpt TEXT,
    content_hash CHAR(64),
    relevance_score DOUBLE PRECISION,
    metadata TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_review_evidence_task_id
        FOREIGN KEY (task_id) REFERENCES review_tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_evidence_finding_id
        FOREIGN KEY (finding_id) REFERENCES findings(id) ON DELETE CASCADE,
    CONSTRAINT chk_review_evidence_line
        CHECK ((start_line IS NULL AND end_line IS NULL) OR
               (start_line IS NOT NULL AND end_line IS NOT NULL AND end_line >= start_line))
);

-- 2.11 Tamper-evident audit event table.
CREATE TABLE IF NOT EXISTS review_audit_events (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    stage VARCHAR(64),
    actor VARCHAR(128),
    message TEXT,
    payload_hash CHAR(64),
    previous_hash CHAR(64),
    event_hash CHAR(64) NOT NULL,
    signature_key_id VARCHAR(128),
    signature_algorithm VARCHAR(32),
    event_signature VARCHAR(128),
    metadata TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_review_audit_events_task_id
        FOREIGN KEY (task_id) REFERENCES review_tasks(id) ON DELETE CASCADE
);

-- 2.12 Spring AI PGVector 向量存储表
CREATE TABLE IF NOT EXISTS vector_store (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    content TEXT,
    metadata JSON,
    embedding vector(384)
);

-- 2.9 系统配置表
CREATE TABLE IF NOT EXISTS system_configs (
    config_key VARCHAR(100) PRIMARY KEY,
    config_value TEXT,
    category VARCHAR(50),
    description VARCHAR(255),
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- 3. 创建索引
-- ============================================

-- 3.1 users 表索引
CREATE INDEX IF NOT EXISTS idx_users_status ON users(status);
CREATE INDEX IF NOT EXISTS idx_users_created_at ON users(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_users_username_gin ON users USING gin(username gin_trgm_ops);

-- 3.2 roles 表索引
CREATE INDEX IF NOT EXISTS idx_roles_status ON roles(status);

-- 3.3 permissions 表索引
CREATE INDEX IF NOT EXISTS idx_permissions_resource ON permissions(resource);

-- 3.4 user_roles 表索引
CREATE INDEX IF NOT EXISTS idx_user_roles_user_id ON user_roles(user_id);
CREATE INDEX IF NOT EXISTS idx_user_roles_role_id ON user_roles(role_id);

-- 3.5 role_permissions 表索引
CREATE INDEX IF NOT EXISTS idx_role_permissions_role_id ON role_permissions(role_id);
CREATE INDEX IF NOT EXISTS idx_role_permissions_permission_id ON role_permissions(permission_id);

-- 3.6 review_tasks 表索引
CREATE INDEX IF NOT EXISTS idx_review_tasks_user_id ON review_tasks(user_id);
CREATE INDEX IF NOT EXISTS idx_review_tasks_type ON review_tasks(review_type);
CREATE INDEX IF NOT EXISTS idx_review_tasks_status ON review_tasks(status);
CREATE INDEX IF NOT EXISTS idx_review_tasks_created_at ON review_tasks(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_tasks_status_created_at ON review_tasks(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_tasks_name_gin ON review_tasks USING gin(name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_review_tasks_metadata_gin ON review_tasks USING gin(metadata);

-- 3.2 findings 表索引
CREATE INDEX IF NOT EXISTS idx_findings_task_id ON findings(task_id);
CREATE INDEX IF NOT EXISTS idx_findings_severity ON findings(severity);
CREATE INDEX IF NOT EXISTS idx_findings_category ON findings(category);
CREATE INDEX IF NOT EXISTS idx_findings_task_severity ON findings(task_id, severity);
CREATE INDEX IF NOT EXISTS idx_findings_task_category ON findings(task_id, category);
CREATE INDEX IF NOT EXISTS idx_findings_title_gin ON findings USING gin(title gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_findings_created_at ON findings(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_findings_grounded ON findings(grounded);
CREATE INDEX IF NOT EXISTS idx_findings_evidence_hash ON findings(evidence_hash);

-- 3.3 review_reports 表索引
CREATE INDEX IF NOT EXISTS idx_review_reports_created_at ON review_reports(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_reports_statistics_gin ON review_reports USING gin(statistics);
CREATE INDEX IF NOT EXISTS idx_review_evidence_task_id ON review_evidence(task_id);
CREATE INDEX IF NOT EXISTS idx_review_evidence_finding_id ON review_evidence(finding_id);
CREATE INDEX IF NOT EXISTS idx_review_evidence_type ON review_evidence(evidence_type);
CREATE INDEX IF NOT EXISTS idx_review_evidence_content_hash ON review_evidence(content_hash);
CREATE INDEX IF NOT EXISTS idx_review_audit_events_task_id ON review_audit_events(task_id);
CREATE INDEX IF NOT EXISTS idx_review_audit_events_event_hash ON review_audit_events(event_hash);
CREATE INDEX IF NOT EXISTS idx_review_audit_events_signature_key_id ON review_audit_events(signature_key_id);
CREATE INDEX IF NOT EXISTS idx_review_audit_events_created_at ON review_audit_events(created_at DESC);
CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON vector_store USING hnsw (embedding vector_cosine_ops);

-- ============================================
-- 4. 创建触发器函数
-- ============================================

-- 更新时间戳的函数
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Append-only guard for audit/evidence tables. These records are review proof material
-- and must not be changed or removed after insertion, even if a client bypasses JPA.
CREATE OR REPLACE FUNCTION codeguardian_prevent_append_only_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'CodeGuardian append-only table % cannot be %', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- 5. 创建触发器
-- ============================================

-- review_tasks 表触发器
DROP TRIGGER IF EXISTS trigger_review_tasks_updated_at ON review_tasks;
CREATE TRIGGER trigger_review_tasks_updated_at
    BEFORE UPDATE ON review_tasks
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- review_reports 表触发器
DROP TRIGGER IF EXISTS trigger_review_reports_updated_at ON review_reports;
CREATE TRIGGER trigger_review_reports_updated_at
    BEFORE UPDATE ON review_reports
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- review_evidence append-only guard.
DROP TRIGGER IF EXISTS trg_review_evidence_append_only ON review_evidence;
CREATE TRIGGER trg_review_evidence_append_only
    BEFORE UPDATE OR DELETE ON review_evidence
    FOR EACH ROW
    EXECUTE FUNCTION codeguardian_prevent_append_only_mutation();

-- review_audit_events append-only guard.
DROP TRIGGER IF EXISTS trg_review_audit_events_append_only ON review_audit_events;
CREATE TRIGGER trg_review_audit_events_append_only
    BEFORE UPDATE OR DELETE ON review_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION codeguardian_prevent_append_only_mutation();

-- ============================================
-- 6. 添加表注释
-- ============================================

-- users 表注释
COMMENT ON TABLE users IS '用户表';
COMMENT ON COLUMN users.id IS '用户ID，自增主键';
COMMENT ON COLUMN users.username IS '用户名，唯一标识';
COMMENT ON COLUMN users.email IS '邮箱地址，用于登录和通知';
COMMENT ON COLUMN users.password_hash IS '密码哈希值，使用BCrypt加密';
COMMENT ON COLUMN users.status IS '用户状态：ACTIVE(激活)/INACTIVE(未激活)/LOCKED(锁定)';

-- roles 表注释
COMMENT ON TABLE roles IS '角色表';
COMMENT ON COLUMN roles.id IS '角色ID，自增主键';
COMMENT ON COLUMN roles.code IS '角色代码，唯一标识，如：ADMIN、REVIEWER、VIEWER';
COMMENT ON COLUMN roles.name IS '角色名称，如：管理员、审查员、查看者';

-- permissions 表注释
COMMENT ON TABLE permissions IS '权限表';
COMMENT ON COLUMN permissions.id IS '权限ID，自增主键';
COMMENT ON COLUMN permissions.code IS '权限代码，唯一标识，如：QUERY、REVIEW、CONFIG、ADMIN';
COMMENT ON COLUMN permissions.name IS '权限名称，如：查询权限、审查权限、配置权限';

-- user_roles 表注释
COMMENT ON TABLE user_roles IS '用户角色关联表';

-- role_permissions 表注释
COMMENT ON TABLE role_permissions IS '角色权限关联表';

-- review_tasks 表注释
COMMENT ON COLUMN review_tasks.user_id IS '创建用户ID，关联users表';
COMMENT ON TABLE review_tasks IS '代码审查任务表';
COMMENT ON COLUMN review_tasks.id IS '任务ID，自增主键';
COMMENT ON COLUMN review_tasks.name IS '任务名称';
COMMENT ON COLUMN review_tasks.review_type IS '审查类型：PROJECT(项目)/DIRECTORY(目录)/FILE(文件)/SNIPPET(代码片段)/GIT(Git项目)';
COMMENT ON COLUMN review_tasks.scope IS '审查范围，可以是文件路径、目录路径或代码片段';
COMMENT ON COLUMN review_tasks.status IS '任务状态：PENDING(待处理)/RUNNING(运行中)/COMPLETED(已完成)/FAILED(失败)';
COMMENT ON COLUMN review_tasks.created_at IS '任务创建时间';
COMMENT ON COLUMN review_tasks.completed_at IS '任务完成时间';
COMMENT ON COLUMN review_tasks.error_message IS '错误信息，任务失败时记录';
COMMENT ON COLUMN review_tasks.metadata IS '元数据，JSON格式，用于存储扩展信息';

-- findings 表注释
COMMENT ON TABLE findings IS '代码审查发现的问题表';
COMMENT ON COLUMN findings.id IS '发现ID，自增主键';
COMMENT ON COLUMN findings.task_id IS '关联的审查任务ID';
COMMENT ON COLUMN findings.severity IS '严重程度：CRITICAL(严重)/HIGH(高)/MEDIUM(中)/LOW(低)';
COMMENT ON COLUMN findings.title IS '问题标题';
COMMENT ON COLUMN findings.location IS '问题位置，可以是文件路径或代码位置描述';
COMMENT ON COLUMN findings.start_line IS '起始行号';
COMMENT ON COLUMN findings.end_line IS '结束行号';
COMMENT ON COLUMN findings.description IS '问题详细描述';
COMMENT ON COLUMN findings.suggestion IS '修复建议';
COMMENT ON COLUMN findings.diff IS '修复代码差异，Diff格式';
COMMENT ON COLUMN findings.category IS '问题类别：SECURITY(安全)/PERFORMANCE(性能)/BUG(缺陷)/CODE_STYLE(代码风格)/MAINTAINABILITY(可维护性)';

-- review_reports 表注释
COMMENT ON TABLE review_reports IS '代码审查报告表';
COMMENT ON COLUMN review_reports.id IS '报告ID，自增主键';
COMMENT ON COLUMN review_reports.task_id IS '关联的审查任务ID，一对一关系';
COMMENT ON COLUMN review_reports.html_content IS 'HTML格式报告内容';
COMMENT ON COLUMN review_reports.markdown_content IS 'Markdown格式报告内容';
COMMENT ON COLUMN review_reports.statistics IS '统计信息，JSON格式，包含问题数量、严重程度分布等';

-- system_configs 表注释
COMMENT ON TABLE system_configs IS '系统配置表';

-- 2.10 操作日志表
CREATE TABLE IF NOT EXISTS operation_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    username VARCHAR(32),
    operation VARCHAR(128),
    method VARCHAR(128),
    params TEXT,
    time_millis BIGINT,
    ip VARCHAR(64),
    status INTEGER DEFAULT 0,
    error_msg TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE operation_logs IS '系统操作日志表';
COMMENT ON COLUMN operation_logs.user_id IS '用户ID';
COMMENT ON COLUMN operation_logs.username IS '用户名';
COMMENT ON COLUMN operation_logs.operation IS '用户操作';
COMMENT ON COLUMN operation_logs.method IS '请求方法';
COMMENT ON COLUMN operation_logs.params IS '请求参数';
COMMENT ON COLUMN operation_logs.time_millis IS '执行时长(毫秒)';
COMMENT ON COLUMN operation_logs.ip IS 'IP地址';
COMMENT ON COLUMN operation_logs.status IS '状态(0:成功 1:失败)';
COMMENT ON COLUMN operation_logs.error_msg IS '错误信息';

CREATE INDEX IF NOT EXISTS idx_operation_logs_username ON operation_logs(username);
CREATE INDEX IF NOT EXISTS idx_operation_logs_created_at ON operation_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_operation_logs_status ON operation_logs(status);
 
 COMMENT ON COLUMN system_configs.config_key IS '配置键，唯一标识';
COMMENT ON COLUMN system_configs.config_value IS '配置值';
COMMENT ON COLUMN system_configs.category IS '配置分类';
COMMENT ON COLUMN system_configs.description IS '配置描述';


-- ============================================
-- 7. 创建视图
-- ============================================

-- 用户权限视图
CREATE OR REPLACE VIEW v_user_permissions AS
SELECT 
    u.id AS user_id,
    u.username,
    u.email,
    r.id AS role_id,
    r.code AS role_code,
    r.name AS role_name,
    p.id AS permission_id,
    p.code AS permission_code,
    p.name AS permission_name,
    p.resource,
    p.action
FROM users u
JOIN user_roles ur ON u.id = ur.user_id
JOIN roles r ON ur.role_id = r.id
JOIN role_permissions rp ON r.id = rp.role_id
JOIN permissions p ON rp.permission_id = p.id
WHERE u.status = 0 AND r.status = 0;

COMMENT ON VIEW v_user_permissions IS '用户权限视图，包含用户的所有角色和权限信息';

-- 任务统计视图
CREATE OR REPLACE VIEW v_task_statistics AS
SELECT 
    t.id AS task_id,
    t.name AS task_name,
    t.review_type,
    t.status,
    t.created_at,
    t.completed_at,
    COUNT(f.id) AS total_findings,
    COUNT(CASE WHEN f.severity = 0 THEN 1 END) AS critical_count,
    COUNT(CASE WHEN f.severity = 1 THEN 1 END) AS high_count,
    COUNT(CASE WHEN f.severity = 2 THEN 1 END) AS medium_count,
    COUNT(CASE WHEN f.severity = 3 THEN 1 END) AS low_count,
    CASE 
        WHEN t.completed_at IS NOT NULL AND t.created_at IS NOT NULL 
        THEN EXTRACT(EPOCH FROM (t.completed_at - t.created_at))
        ELSE NULL 
    END AS duration_seconds
FROM review_tasks t
LEFT JOIN findings f ON t.id = f.task_id
GROUP BY t.id, t.name, t.review_type, t.status, t.created_at, t.completed_at;

COMMENT ON VIEW v_task_statistics IS '任务统计视图，包含每个任务的问题统计信息';

-- 问题分类统计视图
CREATE OR REPLACE VIEW v_finding_category_statistics AS
SELECT 
    category,
    severity,
    COUNT(*) AS count,
    ROUND(AVG(confidence), 2) AS avg_confidence
FROM findings
WHERE category IS NOT NULL
GROUP BY category, severity
ORDER BY category, severity;

COMMENT ON VIEW v_finding_category_statistics IS '问题分类统计视图，按类别和严重程度统计';

-- ============================================
-- 8. 创建存储过程
-- ============================================

-- 检查用户权限存储过程
CREATE OR REPLACE FUNCTION check_user_permission(
    p_user_id BIGINT,
    p_permission_code VARCHAR(32)
)
RETURNS BOOLEAN AS $$
DECLARE
    has_permission BOOLEAN;
BEGIN
    SELECT COUNT(*) > 0 INTO has_permission
    FROM users u
    JOIN user_roles ur ON u.id = ur.user_id
    JOIN roles r ON ur.role_id = r.id
    JOIN role_permissions rp ON r.id = rp.role_id
    JOIN permissions p ON rp.permission_id = p.id
    WHERE u.id = p_user_id 
      AND u.status = 0 
      AND r.status = 0
      AND (p.code = p_permission_code OR p.code = 'ADMIN');
    
    RETURN has_permission;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION check_user_permission IS '检查用户是否有特定权限';

-- 清理过期数据存储过程
CREATE OR REPLACE FUNCTION cleanup_old_tasks(days_to_keep INTEGER DEFAULT 90)
RETURNS TABLE(deleted_tasks BIGINT, deleted_findings BIGINT, deleted_reports BIGINT) AS $$
DECLARE
    task_count BIGINT;
    finding_count BIGINT;
    report_count BIGINT;
BEGIN
    -- 删除关联的发现
    WITH deleted_findings_cte AS (
        DELETE FROM findings 
        WHERE task_id IN (
            SELECT id FROM review_tasks 
            WHERE status = 2 
            AND completed_at < CURRENT_TIMESTAMP - INTERVAL '1 day' * days_to_keep
        )
        RETURNING id
    )
    SELECT COUNT(*) INTO finding_count FROM deleted_findings_cte;
    
    -- 删除关联的报告
    WITH deleted_reports_cte AS (
        DELETE FROM review_reports 
        WHERE task_id IN (
            SELECT id FROM review_tasks 
            WHERE status = 2 
            AND completed_at < CURRENT_TIMESTAMP - INTERVAL '1 day' * days_to_keep
        )
        RETURNING id
    )
    SELECT COUNT(*) INTO report_count FROM deleted_reports_cte;
    
    -- 删除任务
    WITH deleted_tasks_cte AS (
        DELETE FROM review_tasks 
        WHERE status = 'COMPLETED' 
        AND completed_at < CURRENT_TIMESTAMP - INTERVAL '1 day' * days_to_keep
        RETURNING id
    )
    SELECT COUNT(*) INTO task_count FROM deleted_tasks_cte;
    
    RETURN QUERY SELECT task_count, finding_count, report_count;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION cleanup_old_tasks IS '清理超过指定天数的已完成任务及其关联数据';

-- ============================================
-- 脚本执行完成
-- ============================================
