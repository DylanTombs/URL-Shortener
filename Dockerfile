# ── Stage 1: Build ────────────────────────────────────────────────────────────
# Separate build stage so Maven, full JDK, and downloaded dependencies are not
# shipped in the production image. Layer ordering is intentional:
#   1. Copy pom.xml and pre-download dependencies (cached layer — reused on src-only changes)
#   2. Copy source and compile
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ───────────────────────────────────────────────────────────
# JRE-only Alpine image (~180 MB vs ~500 MB full JDK). Non-root user required
# by ECS security best practice and SOC 2 / CIS benchmarks.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
