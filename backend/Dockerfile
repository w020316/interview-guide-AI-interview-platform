# 后端 Dockerfile（适配 Render 免费层 512MB）
# 多阶段构建，减小最终镜像体积
#
# 注意：Render 免费层构建时间有限制（500 分钟/月）
# 此 Dockerfile 优化了构建速度

# ===== 阶段 1：构建 =====
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 先复制 pom.xml，利用 Docker 缓存加速依赖下载
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 再复制源码，仅编译
COPY src ./src
RUN mvn clean package -DskipTests -B -q

# ===== 阶段 2：运行 =====
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 安装必要工具
RUN apk add --no-cache curl tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 复制构建产物
COPY --from=builder /build/target/interview-guide.jar app.jar

# 暴露端口（Render 会注入 PORT 环境变量）
EXPOSE 8080

# JVM 参数（适配 Render 免费层：512MB 内存 + 极低 CPU 配额）
#
# ⚠️ 运行时真正生效的是 Render 上的 JAVA_OPTS 环境变量（见 render.yaml 第 8 节），
#    这里的 ENV 只是「没配环境变量时的默认值」。改参数请**两处同步**。
#
# -XX:TieredStopAtLevel=1（2026-09-22 新增）：JIT 只用 C1、不创建 C2 编译线程。
#   免费层 CPU 被严重限制，冷启动实测 209~488s（中位 ~256s），耗时几乎全在
#   JVM 启动 + Spring 上下文刷新；省下 C2 编译线程的 CPU 争抢能实打实加速启动。
#   Metaspace 必须 ≥128m，否则 Spring Boot + AI + Security 栈类加载失败。
ENV JAVA_OPTS="-Xmx220m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:+UseContainerSupport -XX:TieredStopAtLevel=1"
ENV SPRING_PROFILES_ACTIVE=prod

# 启动命令（显式指定 prod profile，避免环境变量未生效）
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=prod --server.port=${PORT:-8080}"]
