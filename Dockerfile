FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY docs/ docs/
COPY src/ src/
RUN mvn -q clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 arm112
COPY --from=build /workspace/target/arm112-backend-*.jar app.jar
USER arm112
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
