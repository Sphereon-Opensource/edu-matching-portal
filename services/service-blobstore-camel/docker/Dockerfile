FROM eclipse-temurin:21-jre
RUN groupadd -r portal && useradd -r -g portal -u 10001 portal
WORKDIR /app

COPY services/service-blobstore-camel/build/libs/service-blobstore-camel-*-all.jar server.jar
COPY services/service-blobstore-camel/config/ config/

RUN mkdir -p /app/data/blobstores /app/tmp && chown -R portal:portal /app
USER portal

EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=3s CMD curl -f http://localhost:8081/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "server.jar"]
