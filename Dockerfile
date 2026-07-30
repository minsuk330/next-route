FROM eclipse-temurin:17-jre

WORKDIR /app
ARG JAR_FILE=app.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
