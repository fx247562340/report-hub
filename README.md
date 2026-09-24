# Report Hub

可配置的多源关联报表系统：配置上游 HTTP 接口 → 字段映射为数据集 → Join 关联 → 投影成报表。

- **配置元数据**落 PostgreSQL
- **业务数据**实时聚合上游，不落库
- 本服务 **不含** 演示/模拟 ERP、MES 接口

## 端口

| 服务 | 端口 |
|------|------|
| 前端 | **15173** |
| 后端 API | **18080** |
| PostgreSQL | **15433** |

## 技术栈

Java 21 + Spring Boot 3 · React 18 + Vite · PostgreSQL 16 · Docker Compose

## 启动

### 生产 / 一键 Docker（推荐）

```bash
cd report_hub
docker compose up -d --build
```

| 服务 | 地址 |
|------|------|
| 前端 Nginx | http://localhost:15173 |
| API | http://localhost:18080 |
| PostgreSQL | localhost:15433 |

包含三个容器：`report-hub-web`（前端+反代）· `report-hub-api`（Spring Boot）· `report-hub-pg`（PostgreSQL）。

```bash
docker compose ps
docker compose logs -f api
docker compose down          # 停止（数据在 pgdata 卷）
docker compose down -v       # 停止并清库
```

环境变量（可选）：`WEB_PORT` `API_PORT` `PG_PORT` `DB_PASSWORD` `JWT_SECRET` `CORS_ORIGINS`

### 本地开发

```bash
docker compose up -d db          # 仅起 PostgreSQL :15433

cd backend
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn spring-boot:run              # API :18080

cd ../frontend
npm install && npm run dev       # http://127.0.0.1:15173（代理到 18080）
```

## 账号

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | admin123 | 配置管理 |
| member | member123 | 仅查询 |

## 登录会话 / U9C OAuth

- form/JSON 登录、MD5 签名（模板+盐）、Cookie、access_token、401 自动重登  
- **U9C OpenAPI**：`authType=U9C OAuth`，`AuthLogin` 换 token（5 分钟）自动续期  
- RM-MES 字段示例：`sample/datasource-session-ruima.json`

## 关联取数

`dual_list` 双侧列表 · `child_batch` 批量 IN · `child_lookup` 逐条单查
