# syntax=docker/dockerfile:1

# ============================================================
#  Build stage：安装依赖并构建 Vite 静态资源
# ============================================================
FROM node:22-alpine AS build
WORKDIR /app

# 先复制依赖清单，利用 Docker 层缓存（源码变更时无需重新 npm ci）
COPY package.json package-lock.json ./
RUN npm ci

# 复制源码并构建（tsc 类型检查 + vite build）
COPY . .
RUN npm run build

# ============================================================
#  Runtime stage：nginx 静态服务 + /api 反向代理到 backend
# ============================================================
FROM nginx:1.27-alpine AS runtime

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
