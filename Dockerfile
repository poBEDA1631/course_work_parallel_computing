FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/target/search.jar search.jar
ENV PORT=8080
EXPOSE 8080
# The whole index lives on the heap; let it use most of the container memory.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/search.jar"]
