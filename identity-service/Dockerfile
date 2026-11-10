FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/identity-service-*.jar app.jar
USER 10001
EXPOSE 8093
ENTRYPOINT ["java","-jar","/app/app.jar"]
