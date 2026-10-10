ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17
ARG JRE_IMAGE=eclipse-temurin:17-jre
FROM ${MAVEN_IMAGE} AS build
WORKDIR /app
ARG MAVEN_MIRROR_URL=
COPY pom.xml .
COPY docker-maven-settings.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mvn -B -ntp -s docker-maven-settings.xml -DskipTests package; \
    else \
      mvn -B -ntp -DskipTests package; \
    fi

FROM ${JRE_IMAGE}
WORKDIR /app
COPY --from=build /app/target/venue-reservation-backend-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
