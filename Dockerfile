FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY backend/target/golden-hour-*.jar app.jar

ENV ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY}
ENV JWT_SECRET=${JWT_SECRET}
ENV WORLDTIDES_API_KEY=${WORLDTIDES_API_KEY}
ENV SPRING_DATASOURCE_URL=jdbc:h2:file:/data/goldenhour;MODE=MySQL
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O- http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
