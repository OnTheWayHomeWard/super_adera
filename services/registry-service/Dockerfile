# Build stage: compile and package with Maven on Java 21.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package && cp target/registry-service-*.jar /app.jar

# Run stage: small JRE image, non-root user. No secrets are baked in: configuration comes from the
# environment and Infisical at start.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
COPY --from=build /app.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
