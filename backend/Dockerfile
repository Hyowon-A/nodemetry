FROM maven:3.9.16-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
COPY src ./src

RUN mvn -B -DskipTests package \
    && cp target/*.jar /app.jar

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --create-home --home-dir /home/app --shell /usr/sbin/nologin appuser

COPY --chown=appuser:appuser --from=build /app.jar app.jar

USER appuser

ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT:-8080} -jar /app/app.jar"]
