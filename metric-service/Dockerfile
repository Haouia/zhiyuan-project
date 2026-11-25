FROM python:3.12-slim
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="rag-knowledge-metric-service" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.vendor="rag-knowledge"
WORKDIR /app
COPY server.py .
USER 65532:65532
EXPOSE 8090
CMD ["python", "-u", "server.py"]
