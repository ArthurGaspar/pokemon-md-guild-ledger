# One image per service, built from the repository root (catalog bundles ../shared/catalog.json):
#   docker build -f expeditions/Dockerfile --build-arg SERVICE=queue-service .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY shared ./shared
COPY expeditions ./expeditions
ARG SERVICE
RUN --mount=type=cache,target=/root/.m2 \
    cd expeditions && sh mvnw -q -B -pl ${SERVICE} -am package -DskipTests \
    && cp ${SERVICE}/target/${SERVICE}-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 guild
COPY --from=build /src/app.jar app.jar
USER guild
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
