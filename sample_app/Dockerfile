# Multi-stage Docker build for Maven Java application
# Stage 1: Build with Maven and OpenJDK
FROM openjdk:17-jdk-slim AS builder

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy Maven configuration
COPY pom.xml .

# Download dependencies (for better layer caching)
RUN mvn dependency:resolve

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Stage 2: Runtime with Garden Linux and SapMachine
FROM ghcr.io/gardenlinux/gardenlinux/bare-sapmachine:1877.1

# Set working directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/target/server-info-server.jar ./server-info-server.jar

# Expose the application port
EXPOSE 8080

# Set JVM options for minimal resource usage (suitable for phones)
ENV JAVA_OPTS="-Xms32m -Xmx128m -XX:+UseSerialGC -XX:+TieredCompilation -XX:TieredStopAtLevel=1"

# Run the application with the JAR directly (no shell required)
CMD ["java", "-Xms32m", "-Xmx128m", "-XX:+UseSerialGC", "-XX:+TieredCompilation", "-XX:TieredStopAtLevel=1", "-jar", "server-info-server.jar"]
