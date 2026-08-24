# ShopMind 部署（DEPLOYMENT）

> 本文档属于 P1-5 工程知识库，**以当前 Docker 相关文件为唯一事实来源**。
> 覆盖 Docker Compose 部署、环境变量、服务端口、MongoDB volume、Qwen/DeepSeek 配置与启停流程。

---

## 1. 部署拓扑

三服务 Docker Compose 拓扑（[docker-compose.yml](file:///d:/A_big/ShopMind/docker-compose.yml)）：

```
Browser
   │  http://localhost:80
   ▼
frontend (Nginx 1.27-alpine)
   │  /api/ 反向代理（proxy_buffering off 保障 SSE）
   ▼
backend (Spring Boot, :8080)
   │  mongodb://mongodb:27017/shopmind
   ▼
mongodb (Mongo 7.0, :27017, named volume mongodb_data)
```

三个服务通过自定义 bridge 网络 `shopmind` 互联，服务间用**服务名**访问（如 `mongodb:27017`），不依赖 `localhost`。

---

## 2. 服务清单

| 服务 | 镜像/构建 | 容器名 | 端口映射 | 职责 |
|------|-----------|--------|----------|------|
| `mongodb` | `mongo:7.0` | `shopmind-mongodb` | `27017:27017` | 会话记忆持久化 |
| `backend` | 构建 `./backend` | `shopmind-backend` | `8080:8080` | Spring Boot 后端（SSE API） |
| `frontend` | 构建 `./frontend` | `shopmind-frontend` | `80:80` | Nginx 静态资源 + `/api` 反向代理 |

- 全部 `restart: unless-stopped`（[docker-compose.yml](file:///d:/A_big/ShopMind/docker-compose.yml#L5-L6)）。
- 启动依赖：`backend` 依赖 `mongodb` **健康检查通过**（`condition: service_healthy`）；`frontend` 依赖 `backend`（[L26-L28](file:///d:/A_big/ShopMind/docker-compose.yml#L26-L28)、[L50-L51](file:///d:/A_big/ShopMind/docker-compose.yml#L50-L51)）。

---

## 3. MongoDB 健康检查与 Volume

**健康检查**（[docker-compose.yml](file:///d:/A_big/ShopMind/docker-compose.yml#L14-L19)）：
```yaml
healthcheck:
  test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"]
  interval: 10s
  timeout: 5s
  retries: 5
  start_period: 10s
```

**持久化卷**（[L57-L58](file:///d:/A_big/ShopMind/docker-compose.yml#L57-L58)）：
```yaml
volumes:
  mongodb_data:   # 挂载到 mongodb 容器 /data/db
```

- 会话记忆数据落盘在 named volume `mongodb_data`，容器重启/重建不丢数据。
- 删除该 volume 会清空全部会话记忆（见第 8 节 `down -v`）。

---

## 4. 环境变量

Compose 中 `backend` 服务的环境变量（[docker-compose.yml](file:///d:/A_big/ShopMind/docker-compose.yml#L29-L39)）：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `default` | profile 切换：`default`(Mock) / `qwen` / `deepseek` |
| `MONGODB_URI` | `mongodb://mongodb:27017/shopmind` | 服务名访问 MongoDB，覆盖 [application.yml](file:///d:/A_big/ShopMind/backend/src/main/resources/application.yml#L8) 默认值 |
| `QWEN_API_KEY` | 空 | Qwen/DashScope Key |
| `QWEN_CHAT_MODEL` | `qwen-plus` | Qwen 对话模型 |
| `QWEN_EMBEDDING_MODEL` | `text-embedding-v3` | Qwen Embedding 模型 |
| `DEEPSEEK_API_KEY` | 空 | DeepSeek Key |
| `DEEPSEEK_CHAT_MODEL` | `deepseek-v4-flash` | DeepSeek 对话模型 |

变量取值优先级：Compose 读取 `.env` 文件或 shell 环境变量（`${VAR:-default}` 语法），未设置时用默认值。

**`.env` 模板**：[.env.example](file:///d:/A_big/ShopMind/.env.example)，复制为 `.env` 后填入真实 Key（`.env` 已被 `.gitignore` 忽略）。

---

## 5. Docker 镜像构建细节

### 5.1 Backend 镜像（多阶段，[backend/Dockerfile](file:///d:/A_big/ShopMind/backend/Dockerfile)）

| 阶段 | 基础镜像 | 动作 |
|------|----------|------|
| build | `maven:3.9-eclipse-temurin-17` | 先 `COPY pom.xml` + `go-offline` 缓存依赖 → 再 `COPY src` + `package -DskipTests` |
| runtime | `eclipse-temurin:17-jre` | 仅拷贝 fat jar，`EXPOSE 8080`，`TZ=Asia/Shanghai` |

关键点：
- 依赖缓存分层：先复制 `pom.xml` 执行 `dependency:go-offline`，源码变更时复用依赖层（[L9-L11](file:///d:/A_big/ShopMind/backend/Dockerfile#L9-L11)）。
- 测试在镜像内被跳过（`-DskipTests`），测试在本地/CI 执行（[L14-L15](file:///d:/A_big/ShopMind/backend/Dockerfile#L14-L15)）。
- 产物名硬编码 `shopmind-enterprise-1.0.0-SNAPSHOT.jar`（[L25](file:///d:/A_big/ShopMind/backend/Dockerfile#L25)），若 `pom.xml` 的 artifactId/version 变更需同步改。
- 入口：`java $JAVA_OPTS -jar app.jar`，`JAVA_OPTS` 可注入 JVM 参数（[L33-L35](file:///d:/A_big/ShopMind/backend/Dockerfile#L33-L35)）。

### 5.2 Frontend 镜像（多阶段，[frontend/Dockerfile](file:///d:/A_big/ShopMind/frontend/Dockerfile)）

| 阶段 | 基础镜像 | 动作 |
|------|----------|------|
| build | `node:22-alpine` | 先 `COPY package.json package-lock.json` + `npm ci` → 再 `COPY .` + `npm run build` |
| runtime | `nginx:1.27-alpine` | 拷贝 `dist` 到 `/usr/share/nginx/html` + `nginx.conf` 到 `/etc/nginx/conf.d/default.conf` |

- 构建命令含 `tsc` 类型检查 + `vite build`（[L15](file:///d:/A_big/ShopMind/frontend/Dockerfile#L15)）。

---

## 6. Nginx SSE 反向代理（关键）

[nginx.conf](file:///d:/A_big/ShopMind/frontend/nginx.conf) 中 `/api/` 代理到 `http://backend:8080`：

```nginx
location /api/ {
    proxy_pass http://backend:8080;
    proxy_http_version 1.1;
    proxy_buffering off;      # SSE 关键：关闭缓冲，token 实时透传
    proxy_cache off;
    proxy_read_timeout 3600s; # 长连接不超时
    proxy_set_header Connection '';
    proxy_set_header Accept 'text/event-stream';
}
```

- `proxy_buffering off` + `proxy_cache off` 是 SSE 流式输出的必要条件，否则 token 会被 Nginx 缓冲造成"白屏干等"。
- `/` 路径用 `try_files ... /index.html` 做 SPA 路由回退（[L10](file:///d:/A_big/ShopMind/frontend/nginx.conf#L10)）。

---

## 7. Qwen / DeepSeek 配置切换

通过 `SPRING_PROFILES_ACTIVE` + 对应 API Key 切换：

| 目标 | `.env` 配置 |
|------|-------------|
| Mock（默认，无需 Key） | `SPRING_PROFILES_ACTIVE=default` |
| Qwen 全链路（含真实 Embedding/RAG） | `SPRING_PROFILES_ACTIVE=qwen` + `QWEN_API_KEY=sk-xxx` |
| DeepSeek（真实 LLM，RAG 不可用） | `SPRING_PROFILES_ACTIVE=deepseek` + `DEEPSEEK_API_KEY=sk-xxx` |

> 注意：DeepSeek 无 Embedding API，`deepseek` profile 下 RAG 语义检索退化（详见 [LLM_CONFIGURATION.md](./LLM_CONFIGURATION.md#53-deepseek-embedding-能力限制重点)）。要获得真实 RAG，必须用 `qwen` profile。

---

## 8. 启动 / 停止流程

```powershell
# 1) 准备环境变量（首次）
Copy-Item .env.example .env   # 填入真实 API Key / profile

# 2) 构建并后台启动（首次或代码变更后需 --build）
docker compose up -d --build

# 3) 查看状态与日志
docker compose ps
docker compose logs -f backend

# 4) 访问
#  前端: http://localhost:80
#  后端: http://localhost:8080

# 5) 停止（保留数据）
docker compose down

# 6) 停止并清除会话数据（删除 mongodb_data volume）
docker compose down -v
```

- `--build` 会重新构建 backend/frontend 镜像；仅改 `.env` 无需 rebuild，重启即可：`docker compose up -d`。
- `down` 保留 named volume；`down -v` 才会删除 `mongodb_data`（会话记忆清空）。

---

## 9. 本地开发（非 Docker）

后端（需本地 MongoDB 或测试用 embedded MongoDB）：
```powershell
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=qwen
```

前端（Vite dev server，`/api` 由 [vite.config.ts](file:///d:/A_big/ShopMind/frontend/vite.config.ts) 代理到 `http://localhost:8080`）：
```powershell
cd frontend
npm install
npm run dev
```

> 本地开发时前端通过 Vite proxy 转发 `/api` 到后端，SSE 在开发服务器下由 Vite 代理（需确保代理配置未缓冲 SSE；生产由 Nginx 负责，见第 6 节）。
