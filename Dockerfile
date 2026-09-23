# Stage 1: Build
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy only pom.xml first to leverage Docker cache for dependencies
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy source and build (-DskipTests: tests run in CI, not in the image build)
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Security: create a non-root user with a fixed numeric UID (required for runAsNonRoot in Kubernetes)
RUN addgroup -S transactgroup && adduser -S -u 10001 transactuser -G transactgroup

# Copy the artifact from the build stage
COPY --from=build /app/target/*.jar app.jar

USER transactuser

# Expose the application port
EXPOSE 8080

# Container-level health check against the actuator health endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# Use a safe ENTRYPOINT
ENTRYPOINT ["java", "-jar", "app.jar"]
