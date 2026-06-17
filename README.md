# CodeGuardian AI

CodeGuardian AI 是一个基于 Spring Boot 和 Java 21 的智能代码审查系统。它可以对代码片段、文件、目录、项目和 Git 仓库进行审查，结合规则库、RAG 知识库、静态分析结果和 AI 模型生成可追溯的审查报告。

## 功能概览

- 代码片段、单文件、目录、项目和 Git 仓库审查
- AI 模型接入，支持 OpenAI 兼容接口、Qwen 和本地 Ollama
- RAG 知识库管理，支持文档上传、解析、检索和证据引用
- BM25 + PGVector 混合检索
- HTML、Markdown、PDF 等报告生成能力
- 用户、角色、权限管理
- CI/CD API、SARIF 输出和质量门禁
- 审查证据链、运行清单、证明包和审计日志

## 技术栈

- Java 21
- Spring Boot 3.4.x
- Spring AI
- PostgreSQL + pgvector
- Redis
- MinIO
- Thymeleaf
- Maven
- Python 文档解析辅助脚本

## 安全配置

仓库中不提交真实密钥、真实服务地址、数据库导出或本机运行记录。运行时请通过环境变量或本机 `.env` 注入敏感配置。

可复制 `.env.example` 作为本地模板：

```bash
cp .env.example .env
```

常用变量：

```bash
DB_URL=jdbc:postgresql://localhost:5432/code_guardian
DB_USERNAME=postgres
DB_PASSWORD=<your-db-password>

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=<your-redis-password>
REDIS_DATABASE=0

MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=<your-minio-access-key>
MINIO_SECRET_KEY=<your-minio-secret-key>
MINIO_BUCKET=code-review

OPENAI_API_KEY=<your-openai-api-key>
DASHSCOPE_API_KEY=<your-qwen-api-key>
CODEGUARDIAN_CI_TOKEN=<your-ci-token>
```

不要把 `.env`、数据库 dump、日志、IDE 配置、`.claude`、`.cursor`、`.trellis`、`target` 或评测运行产物提交到仓库。

## 本地启动

准备依赖：

- JDK 21+
- Maven 3.8+
- PostgreSQL 15+，并安装 `pg_trgm`、`btree_gin`、`vector`、`uuid-ossp` 扩展
- Redis 7+
- MinIO，可选但上传知识库文件时需要
- Python 3.10+，可选，文档解析增强功能需要

初始化数据库：

```bash
createdb -U <db_user> code_guardian
psql -U <db_user> -d code_guardian -f database/schema.sql
psql -U <db_user> -d code_guardian -f database/init_permissions.sql
```

配置环境变量后启动：

```bash
mvn clean compile
mvn spring-boot:run
```

访问：

- 应用首页：http://localhost:7003
- 登录页：http://localhost:7003/login
- 健康检查：http://localhost:7003/actuator/health

管理员账号不使用固定默认密码。请用自己的 BCrypt hash 初始化首个管理员，示例模板见 `database/README.md`。

## Docker Compose

`docker-compose.yml` 会启动 PostgreSQL 和 Redis。为了避免提交默认口令，Compose 要求先设置密码变量：

```bash
export DB_PASSWORD=<your-db-password>
export REDIS_PASSWORD=<your-redis-password>
docker compose up -d
```

Windows PowerShell：

```powershell
$env:DB_PASSWORD="<your-db-password>"
$env:REDIS_PASSWORD="<your-redis-password>"
docker compose up -d
```

## 文档解析依赖

如果启用 RAG 文档解析脚本，可安装 Python 依赖：

```bash
pip install -r scripts/rag_parser_requirements.txt
```

相关配置：

```bash
RAG_PARSER_ENABLED=true
RAG_PARSER_PYTHON=python
RAG_PARSER_SCRIPT=scripts/rag_parse_document.py
```

## 常用命令

```bash
# 编译
mvn clean compile

# 运行测试
mvn test

# 跳过测试打包
mvn -DskipTests package

# 启动应用
mvn spring-boot:run
```

## 项目结构

```text
.
├── database/                  # PostgreSQL schema 与 RBAC 初始化脚本
├── scripts/                   # 文档解析等辅助脚本
├── src/main/java/             # 后端业务代码
├── src/main/resources/        # 配置、模板、静态资源、规则库
├── src/test/java/             # 单元测试和集成测试
├── tools/rag_eval/            # RAG 评测工具，运行产物不入库
├── docker-compose.yml
├── pom.xml
└── README.md
```

## 上传前检查

提交前建议执行：

```bash
git status --short
git check-ignore -v .env setup.md database/code_guardian_full_dump.sql tools/rag_eval/runs/latest
rg -n -i "api[-_]?key|secret|password|token|access[-_]?key|private[-_]?key" .
mvn -q -DskipTests compile
```

如果扫描命中测试用例或规则样例中的假密钥，需要确认它们只是安全规则测试数据，不是真实凭证。
