# Multi-stage build for Spring Boot application
FROM eclipse-temurin:21-jdk-alpine AS builder
RUN apk add --no-cache maven git
WORKDIR /app

# Layer 1: Copy POM + settings
COPY backend/pom.xml pom.xml
COPY backend/.m2/settings.xml /root/.m2/settings.xml
# Stage solar-utils to a temp path so it can be installed into the cache-mounted .m2
COPY backend/.m2/repository/com/gregochr/solar-utils /tmp/solar-utils-repo/com/gregochr/solar-utils

# Install solar-utils into the BuildKit cache volume and resolve all dependencies.
# The cache mount persists across builds on this host — dependency download is a one-time cost.
RUN --mount=type=cache,target=/root/.m2 \
    cp -r /tmp/solar-utils-repo/com /root/.m2/repository/ && \
    mvn dependency:go-offline -B

# Layer 2: Copy config files and source code, then build
COPY backend/checkstyle.xml backend/spotbugs-exclude.xml backend/dependency-check-suppressions.xml ./
COPY backend/src src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -Dspring-boot.run.profiles=prod -o

# Runtime image
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl

# Create non-root user for security
RUN addgroup -g 1001 appgroup && \
    adduser -D -u 1001 -G appgroup appuser

WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
RUN mkdir -p /app/logs && chown appuser:appgroup /app/logs

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8082/actuator/health || exit 1

USER appuser
EXPOSE 8082

ARG APP_VERSION=dev
ENV APP_VERSION=${APP_VERSION}

ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]
