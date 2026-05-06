FROM eclipse-temurin:21-jre
RUN groupadd -r portal && useradd -r -g portal -u 10001 portal
WORKDIR /app
COPY services/service-sts/build/libs/service-sts-*-all.jar server.jar
COPY services/service-sts/config config
# PKCS12 keystore for the AS signing key — must be writable by the portal user.
RUN mkdir -p /app/data/keystore && chown -R portal:portal /app/data

EXPOSE 8092
USER portal

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD curl -f http://localhost:8092/health || exit 1

ENTRYPOINT ["java", "-jar", "server.jar"]
