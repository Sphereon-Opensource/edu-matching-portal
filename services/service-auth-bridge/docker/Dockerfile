FROM eclipse-temurin:21-jre
RUN groupadd -r portal && useradd -r -g portal -u 10001 portal
WORKDIR /app
COPY services/service-auth-bridge/build/libs/service-auth-bridge-*-all.jar server.jar
COPY services/service-auth-bridge/config config
RUN mkdir -p /app/data/kottage /app/data/keystore && chown -R portal:portal /app/data

EXPOSE 8090
USER portal

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD curl -f http://localhost:8090/health || exit 1

ENTRYPOINT ["java", "-jar", "server.jar"]
