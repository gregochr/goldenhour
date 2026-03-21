# Multi-stage build for Spring Boot application
FROM eclipse-temurin:21-jdk-alpine AS builder
RUN apk add --no-cache maven git
WORKDIR /app

# Layer 1: Copy POM + settings and resolve dependencies (cached until pom.xml changes)
COPY backend/pom.xml pom.xml
COPY backend/.m2/settings.xml /root/.m2/settings.xml
# Pre-install solar-utils from vendored artifacts so GitHub Packages auth is not required
COPY backend/.m2/repository/com/gregochr/solar-utils /root/.m2/repository/com/gregochr/solar-utils
RUN mvn dependency:go-offline -B

# Layer 2: Copy config files and source code, then build
COPY backend/checkstyle.xml backend/spotbugs-exclude.xml backend/dependency-check-suppressions.xml ./
COPY backend/src src
RUN mvn package -DskipTests -Dspring-boot.run.profiles=prod -o

# Runtime image
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl

# Create non-root user for security
RUN addgroup -g 1001 appgroup && \
    adduser -D -u 1001 -G appgroup appuser

WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8082/actuator/health || exit 1

USER appuser
EXPOSE 8082

ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]
