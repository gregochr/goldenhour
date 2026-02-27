# Multi-stage build for Spring Boot application
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY . .
# Copy local Maven settings for GitHub Packages authentication
COPY .m2/settings.xml /root/.m2/settings.xml
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests -Dspring-boot.run.profiles=prod

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
