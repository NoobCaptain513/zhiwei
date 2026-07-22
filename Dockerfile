# ---- Stage 1: Build ----
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# 先拷贝依赖声明，利用 Docker 层缓存
COPY pom.xml .
RUN apk add --no-cache maven && mvn dependency:go-offline -B

# 拷贝源码并打包
COPY src ./src
RUN mvn clean package -DskipTests -B && \
    cp target/zhiwei-*.jar /app.jar

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:21-jre-alpine

# 创建非 root 用户
RUN addgroup -S zhiwei && adduser -S zhiwei -G zhiwei

WORKDIR /app

# 拷贝构建产物
COPY --from=builder /app.jar app.jar

# 时区
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo $TZ > /etc/timezone

# 非 root 运行
USER zhiwei

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
