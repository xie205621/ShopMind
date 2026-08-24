# syntax=docker/dockerfile:1

# ============================================================
#  Build stage：编译打包 Spring Boot 应用（Java 17 / Maven）
# ============================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先复制 pom.xml，利用 Docker 层缓存下载依赖（源码变更时无需重新下载）
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline

# 复制源码并打包（跳过测试；测试在本地 / CI 执行）
COPY src ./src
RUN mvn -B -q -DskipTests package

# ============================================================
#  Runtime stage：仅保留 JRE，减小镜像体积
# ============================================================
FROM eclipse-temurin:17-jre
WORKDIR /app

# 从构建阶段拷贝可执行 fat jar
# 注意：若 pom.xml 的 artifactId/version 变更，需同步更新此文件名
COPY --from=build /workspace/target/shopmind-enterprise-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

# 时区（便于日志时间与本地一致）
ENV TZ=Asia/Shanghai

# profile 通过 SPRING_PROFILES_ACTIVE 环境变量指定；API Key 通过环境变量注入，不写死
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
