# 基础镜像按摘要锁定，保证同一 revision 的构建可复现。
# SkyWalking agent 由部署环境的 JAVA_TOOL_OPTIONS 注入，不写死在镜像内。
FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
ARG SERVICE
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="rag-knowledge-${SERVICE}" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.vendor="rag-knowledge" \
      org.opencontainers.image.licenses="Proprietary"
WORKDIR /app
COPY ${SERVICE}/target/${SERVICE}.jar /app/service.jar
USER 65532:65532
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
