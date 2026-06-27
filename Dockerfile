# ==========================================
# Stage 1: Build the JAR using Maven
# ==========================================
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml and download dependencies to leverage Docker layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build the package (skipping tests for faster deployment builds)
COPY src ./src
RUN mvn clean package -DskipTests

# ==========================================
# Stage 2: Run the JAR using a lightweight JRE
# ==========================================
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=build /app/target/*.jar app.jar

# Expose the port the app runs on
EXPOSE 8080

# Run the application with production-optimized settings
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "-Dspring.devtools.restart.enabled=false", "-jar", "-Dserver.port=8080", "app.jar"]
