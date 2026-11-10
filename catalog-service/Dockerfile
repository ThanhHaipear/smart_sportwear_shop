FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -q -DskipTests package
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/target/catalog-service-0.0.1-SNAPSHOT.jar app.jar
USER 65532:65532
EXPOSE 8092
ENTRYPOINT ["java","-jar","/app/app.jar"]
