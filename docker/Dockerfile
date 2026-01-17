FROM openjdk:21-jre-slim

WORKDIR /app

COPY build/libs/hana-orchestrator-*.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]