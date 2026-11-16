# Le contexte de construction est la racine du dépôt.
FROM node:22-alpine AS frontend-checks
WORKDIR /frontend
COPY ["front end/", "./"]
RUN node --check login.js && node --check member.js && node --check member-resets.js && node --check admin-users.js && node --check activate.js && node --check reset-password.js \
    && node --input-type=module --check < library.js && node --input-type=module --check < library-utils.js \
    && node tests/login.test.cjs && node tests/activate.test.cjs && node tests/library.test.cjs

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY --from=frontend-checks ["/frontend/", "/workspace/front end/"]
# PostgreSQL est testé séparément avec Testcontainers ; pas de socket Docker dans le build.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f backend/pom.xml package -Dtest=AuthSecurityTest,LoginThrottleTest,BggImportTest,BggHttpTest

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S cej && adduser -S -G cej cej
WORKDIR /app
COPY --from=build --chown=cej:cej /workspace/backend/target/cej-backend-0.1.0-SNAPSHOT.jar app.jar
USER cej
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -q --spider http://127.0.0.1:8080/ || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
