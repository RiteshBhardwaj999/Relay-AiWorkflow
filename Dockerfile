# syntax=docker/dockerfile:1

# ---- Build stage: compile and package the Spring Boot jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Cache dependencies first for faster rebuilds
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- Runtime stage: small JRE image with just the jar + seed data ----
FROM eclipse-temurin:21-jre
WORKDIR /app
# The node catalog + seed workflows the app loads on startup
COPY relay-capstone-pack/data ./relay-capstone-pack/data
COPY --from=build /build/target/relay-*.jar app.jar
EXPOSE 8080
# JVM is pinned to UTC in code; nothing else needed here.
ENTRYPOINT ["java", "-jar", "app.jar"]
