# Build from the repository root (the API bundles ../shared/catalog.json):
#   docker build -f api/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY shared ./shared
COPY api/.mvn ./api/.mvn
COPY api/mvnw api/pom.xml ./api/
RUN cd api && sh mvnw -q -B dependency:go-offline
COPY api/src ./api/src
RUN cd api && sh mvnw -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 guild
COPY --from=build /src/api/target/guild-ledger-api-*.jar app.jar
USER guild
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
