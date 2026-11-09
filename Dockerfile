FROM eclipse-temurin:17-jre
WORKDIR /app
ARG SERVICE
COPY ${SERVICE}/target/${SERVICE}-1.0.0.jar /app/service.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
