# 构建阶段只接收.dockerignore允许的源码，不复制.env或本地资料。
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl stock-agent-web -am package -Dmaven.test.skip=true
RUN javac --release 8 -d /probe deployment/HealthProbe.java

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# 使用固定非root UID，业务数据交给独立数据库/向量卷管理。
USER 10001:10001
COPY --from=build --chown=10001:10001 /workspace/stock-agent-web/target/stock-agent-web-0.1.0-SNAPSHOT.jar /app/app.jar
COPY --from=build --chown=10001:10001 /probe /app/probe
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=12s --start-period=40s --retries=6 CMD ["java","-Xmx32m","-cp","/app/probe","io.github.sjcud.stockagent.deployment.HealthProbe","http://127.0.0.1:8080/actuator/health/readiness"]
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70.0","-jar","/app/app.jar"]
