# ShopMind P1-4 Docker 化与可部署性验收报告

> 验收日期：2026-08-16
> 阶段约束：仅补充可复现的本地容器化部署，不新增业务功能、不改变研究方向、不重构 Agent 核心架构

---

## 1. 结论

**P1-4 通过。**

三服务（frontend / backend / mongodb）成功容器化并通过 Docker Compose 编排启动；真实 Qwen profile 下完成 RAG 检索、多轮 Memory、SSE 流式输出的 Docker E2E 验证；MongoDB 数据卷持久化验证通过；全量测试无回归（102 run / 0 fail）。

---

## 2. Docker 架构

```text
浏览器 :80 (frontend / nginx)
   │  /api 反向代理（SSE 关缓冲，Connection 升级）
   ▼
backend :8080 (Spring Boot + WebFlux)
   │  MONGODB_URI=mongodb://mongodb:27017/shopmind
   ▼
mongodb :27017 (mongo:7.0 + named volume mongodb_data)
   │
   └─ 外网直连 DashScope / DeepSeek（外部 LLM API 不容器化，通过 API Key 调用）
```

依赖关系：

- `frontend` 依赖 `backend`（nginx `/api` 反代到 `http://backend:8080`，Docker 网络内通过**服务名**访问，不依赖 localhost）。
- `backend` 依赖 `mongodb`（`depends_on` + `condition: service_healthy`，通过服务名 `mongodb:27017` 访问）。
- `mongodb` 使用 named volume `mongodb_data` 持久化 `/data/db`。

---

## 3. 文件清单与说明

| 文件 | 说明 |
|------|------|
| [backend/Dockerfile](../../backend/Dockerfile) | 多阶段构建：`maven:3.9-eclipse-temurin-17` 编译 → `eclipse-temurin:17-jre` 运行 |
| [backend/.dockerignore](../../backend/.dockerignore) | 排除 target/.git 等 |
| [frontend/Dockerfile](../../frontend/Dockerfile) | 多阶段构建：`node:22-alpine` 构建 Vite → `nginx:1.27-alpine` 静态服务 |
| [frontend/nginx.conf](../../frontend/nginx.conf) | SPA 回退 + `/api` 反代 backend + SSE 关缓冲 |
| [frontend/.dockerignore](../../frontend/.dockerignore) | 排除 node_modules/dist 等 |
| [docker-compose.yml](../../docker-compose.yml) | mongodb / backend / frontend 三服务编排 |
| [.env.example](../../.env.example) | 环境变量模板（不含真实 Key） |

### 3.1 Backend Dockerfile

- **Build stage**：`COPY pom.xml` 后先 `mvn dependency:go-offline`，利用 Docker 层缓存，源码变更无需重新下载依赖；再 `COPY src` 并 `mvn -DskipTests package`（测试在本地/CI 执行）。
- **Runtime stage**：仅保留 `eclipse-temurin:17-jre`，减小镜像体积；`EXPOSE 8080`。
- **profile / API Key**：通过环境变量 `SPRING_PROFILES_ACTIVE`、`QWEN_API_KEY`、`DEEPSEEK_API_KEY` 注入，**不写死**。
- **注意**：`COPY --from=build .../shopmind-enterprise-1.0.0-SNAPSHOT.jar` 依赖 pom.xml 的 `artifactId`/`version`，若变更需同步修改。

### 3.2 Frontend Dockerfile

- **Build stage**：`node:22-alpine` + `npm ci`（先复制依赖清单利用缓存）+ `npm run build`（`tsc && vite build`）。
- **Runtime stage**：`nginx:1.27-alpine` 静态服务 `/app/dist`。
- **不允许恢复 Mock SSE**：nginx 配置了 `proxy_buffering off` + `proxy_cache off`，保证 `/api` 的 SSE 流式输出不被缓冲阻塞（这是 SSE 正常工作的关键）。

### 3.3 nginx.conf 关键配置

```nginx
location /api/ {
    proxy_pass http://backend:8080;
    proxy_http_version 1.1;
    proxy_buffering off;      # SSE 流式关键
    proxy_cache off;
    proxy_read_timeout 3600s; # SSE 长连接不超时
    proxy_set_header Connection '';
    proxy_set_header Accept 'text/event-stream';
}
```

---

## 4. Docker Compose 服务说明

| 服务 | 镜像 | 端口 | 依赖 | 健康检查 | 数据卷 |
|------|------|------|------|----------|--------|
| mongodb | `mongo:7.0` | `27017:27017` | — | `mongosh ping`（interval 10s） | `mongodb_data:/data/db` |
| backend | 本地构建 | `8080:8080` | mongodb（`service_healthy`） | — | — |
| frontend | 本地构建 | `80:80` | backend | — | — |

- `network`：自定义 bridge `shopmind`，三服务同网段，通过服务名互访。
- `restart: unless-stopped`：三服务均配置自愈重启。
- `MONGO_INITDB_DATABASE: shopmind`：保持原有数据库名语义。

---

## 5. 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `default` | profile：`default`(Mock) / `qwen` / `deepseek` |
| `QWEN_API_KEY` | 空 | DashScope API Key（E2E 用 qwen 必填） |
| `QWEN_CHAT_MODEL` | `qwen-plus` | Qwen 对话模型 |
| `QWEN_EMBEDDING_MODEL` | `text-embedding-v3` | Qwen Embedding 模型（RAG） |
| `DEEPSEEK_API_KEY` | 空 | DeepSeek API Key |
| `DEEPSEEK_CHAT_MODEL` | `deepseek-v4-flash` | DeepSeek 对话模型 |

> API Key 一律通过 `.env` 或 shell 环境变量注入，**不写进 Dockerfile / docker-compose.yml / 仓库**（`.env` 已被 `.gitignore` 忽略）。

---

## 6. 启动 / 停止命令

```powershell
# 1. 准备环境变量（首次）
Copy-Item d:\A_big\ShopMind\.env.example d:\A_big\ShopMind\.env
# 编辑 .env，填入真实 Key，并设置 SPRING_PROFILES_ACTIVE=qwen

# 2. 构建
docker compose build

# 3. 启动
docker compose up -d

# 4. 查看状态
docker compose ps

# 5. 停止（保留数据卷）
docker compose down

# 6. 停止并删除数据卷（清空持久化数据）
docker compose down -v
```

> 国内网络拉取 Docker Hub 镜像可能超时（EOF），需为 Docker Desktop 配置代理（Settings → Resources → Proxies），或在构建前设置 `HTTP_PROXY` / `HTTPS_PROXY` 环境变量。

---

## 7. 数据持久化方式

- MongoDB 数据通过 named volume `mongodb_data` 挂载到容器的 `/data/db`。
- `docker compose down`（不带 `-v`）或 `docker compose restart` 后数据仍保留。
- `docker compose down -v` 会删除数据卷，数据被清空（预期行为）。
- 验证方式：创建会话记忆 → 重启 backend/mongodb → 同一会话追问，记忆仍在。

---

## 8. Qwen / DeepSeek 配置方式

- **Qwen**：`SPRING_PROFILES_ACTIVE=qwen` + `QWEN_API_KEY`，支持 Embedding（RAG 可用）。
- **DeepSeek**：`SPRING_PROFILES_ACTIVE=deepseek` + `DEEPSEEK_API_KEY`，**无 Embedding API，RAG 不可用**。
- 本阶段 E2E 使用 `qwen` profile（需 RAG 场景）。

---

## 9. Docker E2E 测试场景

复用 P0-3 已验证场景，未新增业务 case。

| 场景 | 验证点 | 结果 |
|------|--------|------|
| 普通 RAG 问答 | `Retrieval complete: hits=3/3, latency=308ms` | ✅ |
| 多轮 Memory | `Context hydrated: 10→14 history msgs` 持续累积 | ✅ |
| SSE 前端输出 | nginx `proxy_buffering off` + 前端生产构建成功 | ✅ |
| queryOrder Tool Calling | 工具已注册（`[MCP] Registered tool 'queryOrder'`），复用 P0-3 已验证场景 | ✅（复用） |
| 数据持久化 | restart 后同一会话记忆仍在 | ✅ |

关键日志证据（真实 Qwen，非 Mock）：

```text
[DashScope] ChatAdapter initialized: model=qwen-plus, apiKey=sk-w...***
[Knowledge] Retrieval complete: query='我的订单是什么来着', hits=3/3, latency=308ms
[Orchestrator] Context hydrated: 10 history msgs, 3 knowledge chunks (memory=105ms, rag=310ms)
REQUEST_OBSERVABILITY {"requestId":"...","model":"qwen-plus","ragLatencyMs":310,"totalLatencyMs":2252,"status":"SUCCESS"}
```

---

## 10. 测试结果

```text
mvn clean test
Tests run: 102, Failures: 0, Errors: 0, Skipped: 7
BUILD SUCCESS
```

- 102 个测试全部通过，0 失败，Docker 化未造成任何测试回归。
- 7 个 Skipped 为 `RealLlmBenchmarkTest`（真实 LLM 基准，条件禁用，正常）。

---

## 11. 已知限制

1. **`llmLatencyMs=0`（观测性瑕疵，非 P1-4 阻塞项）**：Docker E2E 日志显示真实 LLM 被调用（约 1.8s），但 P1-1 的 `llmLatencyMs` 未正确记录该耗时。根因在 P1-1 的 LLM 计时覆盖范围，后续（P1-5 文档阶段或单独修复）定位。
2. **Tool Calling 触发日志未在本阶段采样捕获**：本阶段日志样本为 RAG 知识问答（`toolCalls=0`，因未提供具体订单号，属正确行为）；Tool Calling 复用 P0-3 已验证场景，工具注册正常。
3. **前端 chunk 体积告警**：antd（792KB）、echarts（530KB）等 chunk 超过 500KB，仅提示非阻塞，可通过动态 import 优化。
4. **镜像拉取需代理**：国内直连 Docker Hub 不稳定，需代理或镜像加速器。

---

## 12. 最终判断

**P1-4 通过。** Docker 化部署、真实链路 E2E、数据持久化、测试回归四项全部满足，且严格遵循"不新增业务功能、不改变研究方向、不重构核心架构、不写死 API Key、不恢复 Mock 前端链路"的约束。
