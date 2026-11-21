# Stage 1: build the jar with Maven (tests are run separately with `mvn test`, they need Docker themselves).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# Stage 2: a small Java 21 runtime image, running as a non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --system --create-home appuser
USER appuser
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
