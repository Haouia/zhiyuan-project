FROM python:3.12-slim
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="rag-knowledge-model-fixture" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.vendor="rag-knowledge"
WORKDIR /app
COPY fixture.py .
USER 65532:65532
CMD ["python", "-u", "fixture.py"]
