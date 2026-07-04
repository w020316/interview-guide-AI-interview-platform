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

# JVM 参数（小内存优化）
ENV JAVA_OPTS="-Xmx220m -XX:MaxMetaspaceSize=96m -XX:+UseSerialGC -XX:+UseContainerSupport -XX:MaxRAMPercentage=50.0"
ENV SPRING_PROFILES_ACTIVE=prod

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=${PORT:-8080}"]
