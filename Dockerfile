FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY docs/contracts/ docs/contracts/
COPY src/ src/
# Cache .m2 across builds so a flaky Maven Central timeout does not re-download everything.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -Dhttps.protocols=TLSv1.2 \
    -Dmaven.wagon.http.retryHandler.count=5 \
    -Dmaven.wagon.httpconnectionManager.ttlSeconds=120 \
    clean package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl \
    && addgroup -S -g 10001 arm112 \
    && adduser -S -u 10001 -G arm112 -D arm112 \
    && mkdir -p /data/backups /data/materials /data/logs \
    && chown -R arm112:arm112 /data
COPY --from=build /workspace/target/arm112-backend-*.jar app.jar
USER arm112
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=10 \
    CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
