FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S involens && adduser -S involens -G involens
COPY --from=build --chown=involens:involens /workspace/target/involens_be-0.0.1-SNAPSHOT.jar app.jar

USER involens
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
