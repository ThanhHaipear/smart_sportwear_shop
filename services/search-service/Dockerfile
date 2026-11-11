FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt requirements-embeddings.txt ./
ARG INSTALL_LOCAL_EMBEDDINGS=false
RUN pip install --no-cache-dir -r requirements.txt && \
    if [ "$INSTALL_LOCAL_EMBEDDINGS" = "true" ]; then pip install --no-cache-dir -r requirements-embeddings.txt; fi
COPY app ./app
EXPOSE 8090
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
