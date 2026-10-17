# Built from the repository root, which holds the parent pom:
#   docker build -f cardrail-clearing/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -q -B -pl cardrail-clearing -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home cardrail
WORKDIR /app
COPY --from=build /src/cardrail-clearing/target/cardrail-clearing-*.jar app.jar
USER cardrail
# Sized for a 512 MB instance with a fraction of a CPU: serial GC has no background threads, and C1
# only (tiered level 1) spends less of that CPU compiling while the service starts.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar /app/app.jar"]
