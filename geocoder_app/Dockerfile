# Multi-stage Docker build for K3s Reverse Geocoder Service

# Build stage
FROM docker.io/library/openjdk:17-jdk-slim AS builder

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy Maven configuration
COPY pom.xml .

# Download dependencies (cached layer)
RUN mvn dependency:resolve

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Runtime stage - use lightweight JRE
FROM ghcr.io/gardenlinux/gardenlinux/bare-sapmachine:1877.1

# Set working directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/target/reverse-geocoder.jar ./reverse-geocoder.jar

# Expose port
EXPOSE 8090

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8090/health || exit 1

# Run the application
CMD ["java", "-jar", "reverse-geocoder.jar"]
