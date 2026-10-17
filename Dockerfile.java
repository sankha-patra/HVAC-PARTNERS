# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /source
COPY pom.xml ./
COPY identity-service/pom.xml identity-service/pom.xml
COPY billing-service/pom.xml billing-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
RUN --mount=type=cache,id=invoice-payments-maven,sharing=locked,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY identity-service/src identity-service/src
COPY billing-service/src billing-service/src
COPY api-gateway/src api-gateway/src
RUN --mount=type=cache,id=invoice-payments-maven,sharing=locked,target=/root/.m2 mvn -B -ntp package -DskipTests
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*     && mkdir -p /app/certs && curl --fail --silent --show-error --proto '=https' https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o /app/certs/global-bundle.pem     && groupadd --gid 10001 app && useradd --uid 10001 --gid 10001 --no-create-home app     && chown 10001:10001 /tmp && chmod 1770 /tmp
USER 10001:10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
FROM runtime AS identity
COPY --from=build /source/identity-service/target/identity-service-1.0.0.jar /app/app.jar
EXPOSE 8081
FROM runtime AS billing
COPY --from=build /source/billing-service/target/billing-service-1.0.0.jar /app/app.jar
EXPOSE 8084
FROM runtime AS gateway
COPY --from=build /source/api-gateway/target/api-gateway-1.0.0.jar /app/app.jar
EXPOSE 8080

# Disposable verification image: public dependencies and test source only.
FROM build AS verification
RUN --mount=type=cache,id=invoice-payments-maven,sharing=locked,target=/root/.m2 cp -a /root/.m2 /opt/maven-cache
CMD ["mvn", "-B", "-o", "-Dmaven.repo.local=/opt/maven-cache", "test"]
