FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -q -DskipTests package
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 sales
USER sales
WORKDIR /app
COPY --from=build /workspace/target/sales-service-1.0.0.jar app.jar
EXPOSE 8094
ENTRYPOINT ["java","-jar","app.jar"]
