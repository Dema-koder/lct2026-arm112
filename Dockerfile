FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY docs/contracts/ docs/contracts/
COPY src/ src/
RUN mvn -q clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 arm112
COPY --from=build /workspace/target/arm112-backend-*.jar app.jar
USER arm112
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=10 \
    CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
