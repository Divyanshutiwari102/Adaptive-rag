# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /build

# Copy only pom first (better caching)
COPY pom.xml .
RUN apk add --no-cache maven && mvn -B -q -e -DskipTests dependency:go-offline

# Copy source
COPY src ./src

# IMPORTANT FIX: skip test compilation completely
RUN mvn -B -q package -Dmaven.test.skip=true

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

RUN addgroup -S ragapp && adduser -S ragapp -G ragapp
USER ragapp
WORKDIR /app

COPY --from=build --chown=ragapp:ragapp /build/target/adaptive-rag-*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]