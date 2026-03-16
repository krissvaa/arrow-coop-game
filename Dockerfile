# Stage 1: Build the production JAR
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app
COPY pom.xml .
COPY src ./src

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Build with production profile (Vaadin production bundle + optimized JAR)
RUN mvn clean package -Pproduction -DskipTests

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the production JAR
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Activate production profile at runtime
ENTRYPOINT ["java", \
    "-Xmx512m", \
    "-Dspring.profiles.active=production", \
    "-jar", "app.jar"]
