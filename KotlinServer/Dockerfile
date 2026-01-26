FROM eclipse-temurin:23-jdk AS builder
WORKDIR /app

# Install findutils so that xargs is available
RUN apt-get update && apt-get install -y findutils

# Copy root-level files, including gradle.properties and settings.gradle.kts
COPY gradle.properties .
COPY settings.gradle.kts .

# Copy Gradle wrapper scripts and configuration files
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/

# Copy buildSrc so custom plugins are available
COPY buildSrc/ buildSrc/

# Copy your modules
COPY app/ app/
COPY utils/ utils/

# Ensure the Gradle wrapper is executable
RUN chmod +x gradlew

# Build the fat jar for the 'app' module
RUN ./gradlew :app:clean :app:shadowJar

# Stage 2: Runtime image using Eclipse Temurin JRE
FROM eclipse-temurin:23-jre-alpine
WORKDIR /app

# Copy the generated fat jar from the builder stage
COPY --from=builder /app/app/build/libs/app-all.jar .

COPY .env .env

EXPOSE 80

ENTRYPOINT ["java", "-jar", "app-all.jar"]