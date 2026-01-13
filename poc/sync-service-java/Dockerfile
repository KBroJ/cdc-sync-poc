# ===========================================
# CDC Sync Service - Java/Spring Boot
# ===========================================

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# 의존성 캐싱을 위해 pom.xml 먼저 복사
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 소스 코드 복사 및 빌드
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre

WORKDIR /app

# curl 설치 (healthcheck용)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# JAR 파일 복사
COPY --from=builder /app/target/*.jar app.jar

# 환경변수 기본값
ENV KAFKA_BOOTSTRAP_SERVERS=kafka:29092 \
    ASIS_DB_HOST=asis-oracle \
    ASIS_DB_PORT=1521 \
    ASIS_DB_SERVICE=XEPDB1 \
    ASIS_DB_USER=asis_user \
    ASIS_DB_PASSWORD=asis123 \
    TOBE_DB_HOST=tobe-oracle \
    TOBE_DB_PORT=1521 \
    TOBE_DB_SERVICE=XEPDB1 \
    TOBE_DB_USER=tobe_user \
    TOBE_DB_PASSWORD=tobe123

# JVM 옵션
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

# 포트 노출
EXPOSE 8080

# 헬스체크
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# 실행
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
