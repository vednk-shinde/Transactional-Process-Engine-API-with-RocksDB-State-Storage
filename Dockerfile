# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY proto ./proto
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- Runtime stage (glibc JRE: RocksDB ships native glibc libraries) ----
FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --uid 10001 --home /app engine && mkdir -p /data && chown engine /data
WORKDIR /app
COPY --from=build /workspace/target/process-engine-api-*.jar app.jar
USER engine
ENV STORE=rocksdb DB_PATH=/data HTTP_PORT=8080 GRPC_PORT=9090
EXPOSE 8080 9090
VOLUME /data
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD ["java", "-cp", "/app/app.jar", "com.example.processengine.HealthCheck"]
ENTRYPOINT ["java", "-jar", "app.jar"]
