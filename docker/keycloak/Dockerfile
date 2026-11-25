# 本地 OIDC 桩固定到具体版本；企业环境使用获批的 IdP，不部署此镜像。
FROM quay.io/keycloak/keycloak:26.8.2
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="rag-knowledge-keycloak" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.vendor="rag-knowledge"
COPY --chown=1000:0 rag-local-realm.json /opt/keycloak/data/import/rag-local-realm.json
